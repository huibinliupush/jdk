/*
 * Copyright (c) 2015, 2021, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

#include "precompiled.hpp"
#include "classfile/javaClasses.inline.hpp"
#include "gc/shared/referencePolicy.hpp"
#include "gc/shared/referenceProcessorStats.hpp"
#include "gc/z/zHeap.inline.hpp"
#include "gc/z/zReferenceProcessor.hpp"
#include "gc/z/zStat.hpp"
#include "gc/z/zTask.hpp"
#include "gc/z/zTracer.inline.hpp"
#include "gc/z/zValue.inline.hpp"
#include "memory/universe.hpp"
#include "runtime/atomic.hpp"
#include "runtime/mutexLocker.hpp"
#include "runtime/os.hpp"

static const ZStatSubPhase ZSubPhaseConcurrentReferencesProcess("Concurrent References Process");
static const ZStatSubPhase ZSubPhaseConcurrentReferencesEnqueue("Concurrent References Enqueue");

static ReferenceType reference_type(oop reference) {
  return InstanceKlass::cast(reference->klass())->reference_type();
}

static const char* reference_type_name(ReferenceType type) {
  switch (type) {
  case REF_SOFT:
    return "Soft";

  case REF_WEAK:
    return "Weak";

  case REF_FINAL:
    return "Final";

  case REF_PHANTOM:
    return "Phantom";

  default:
    ShouldNotReachHere();
    return NULL;
  }
}

static volatile oop* reference_referent_addr(oop reference) {
  return (volatile oop*)java_lang_ref_Reference::referent_addr_raw(reference);
}

static oop reference_referent(oop reference) {
  return Atomic::load(reference_referent_addr(reference));
}

static void reference_clear_referent(oop reference) {
  java_lang_ref_Reference::clear_referent(reference);
}

static oop* reference_discovered_addr(oop reference) {
  return (oop*)java_lang_ref_Reference::discovered_addr_raw(reference);
}

static oop reference_discovered(oop reference) {
  return *reference_discovered_addr(reference);
}

static void reference_set_discovered(oop reference, oop discovered) {
  java_lang_ref_Reference::set_discovered_raw(reference, discovered);
}

static oop* reference_next_addr(oop reference) {
  return (oop*)java_lang_ref_Reference::next_addr_raw(reference);
}

static oop reference_next(oop reference) {
  return *reference_next_addr(reference);
}

static void reference_set_next(oop reference, oop next) {
  java_lang_ref_Reference::set_next_raw(reference, next);
}

static void soft_reference_update_clock() {
  const jlong now = os::javaTimeNanos() / NANOSECS_PER_MILLISEC;
  java_lang_ref_SoftReference::set_clock(now);
}

ZReferenceProcessor::ZReferenceProcessor(ZWorkers* workers) :
    _workers(workers),
    _soft_reference_policy(NULL),
    _encountered_count(),
    _discovered_count(),
    _enqueued_count(),
    _discovered_list(NULL),
    _pending_list(NULL),
    _pending_list_tail(_pending_list.addr()) {}

void ZReferenceProcessor::set_soft_reference_policy(bool clear) {
  static AlwaysClearPolicy always_clear_policy;
  static LRUMaxHeapPolicy lru_max_heap_policy;

  if (clear) {
    log_info(gc, ref)("Clearing All SoftReferences");
    _soft_reference_policy = &always_clear_policy;
  } else {
    _soft_reference_policy = &lru_max_heap_policy;
  }

  _soft_reference_policy->setup();
}

bool ZReferenceProcessor::is_inactive(oop reference, oop referent, ReferenceType type) const {
  if (type == REF_FINAL) {
    // A FinalReference is inactive if its next field is non-null. An application can't
    // call enqueue() or clear() on a FinalReference.
    // 在 ReferenceHandler 线程 的 processPendingReferences 方法中
    // 当从 _reference_pending_list 中将 Reference 对象摘下时，ref.discovered = null
    return reference_next(reference) != NULL;
  } else {
    // A non-FinalReference is inactive if the referent is null. The referent can only
    // be null if the application called Reference.enqueue() or Reference.clear().
    // 该 reference 已经被应用程序处理过了 Reference.enqueue() or Reference.clear() 这里就不用重复添加到 discoverlist 中了
    // Reference 类中的 enqueue() 和 clear() 都会调用 native 方法 clear0 将 referent 置为 null
    // ThreadLocal 中的 remove 方法会调用 clear() 方法
    return referent == NULL;
  }
}

bool ZReferenceProcessor::is_strongly_live(oop referent) const {
  return ZHeap::heap()->is_object_strongly_live(ZOop::to_address(referent));
}

bool ZReferenceProcessor::is_softly_live(oop reference, ReferenceType type) const {
  if (type != REF_SOFT) {
    // Not a SoftReference
    return false;
  }

  // Ask SoftReference policy
  const jlong clock = java_lang_ref_SoftReference::clock();
  assert(clock != 0, "Clock not initialized");
  assert(_soft_reference_policy != NULL, "Policy not initialized");
  return !_soft_reference_policy->should_clear_reference(reference, clock);
}

bool ZReferenceProcessor::should_discover(oop reference, ReferenceType type) const {
  volatile oop* const referent_addr = reference_referent_addr(reference);
    // 调整 referent 对象的视图为 remapped + mark0 也就是 weakgood 视图
    // 表示该 referent 对象目前只能通过弱引用链访问到，而不能通过强引用链访问到
    // 注意这里是调整 referent 的视图 而不是调整 reference 的视图
  const oop referent = ZBarrier::weak_load_barrier_on_oop_field(referent_addr);
  // A non-FinalReference is inactive if the referent is null
  // 为什么 inactive 的 reference 不能被放到 discoverlist 中呢 ？
  // 其实是为了避免 reference 被重复放到 discoverlist 中被重复处理
  // 第一次 gc 的时候走到这里只是并发标记阶段，所以 referent 还没有被 gc 设置为 null
  // 后面处理 none strong 引用的时候会将这个 reference 方法 penndinglist 中 第一次 gc 结束
  // 非 gc 阶段，jdk 中的 ReferenceHandler 线程会处理 penndinglist 中的 reference
  // 当我们在应用程序中调用了 Reference.enqueue() or Reference.clear() 的时候，reference 就变成 inactive
  // inactive 的 reference 表示我们已经处理过了，（但这个 reference 可能还被其他 gc root 引用）
  // 在第二次 gc 的时候无论这个 reference 存活与否，这里都不会将这个 reference 放到 discoverlist 中了防止被重复处理
  if (is_inactive(reference, referent, type)) {
    return false;
  }
  // referent 还被强引用关联，那么 return false 也就是说不能被加入到 discover list 中
  // 是否是 gc 之后新申请的对象，是否被  _livemap.get(index + 1) 标记过
  if (is_strongly_live(referent)) {
    return false;
  }
  // referent 还被软引用有效关联，那么 return false 也就是说不能被加入到 discover list 中
  if (is_softly_live(reference, type)) {
    return false;
  }

  // PhantomReferences with finalizable marked referents should technically not have
  // to be discovered. However, InstanceRefKlass::oop_oop_iterate_ref_processing()
  // does not know about the finalizable mark concept, and will therefore mark
  // referents in non-discovered PhantomReferences as strongly live. To prevent
  // this, we always discover PhantomReferences with finalizable marked referents.
  // They will automatically be dropped during the reference processing phase.
  return true;
}

bool ZReferenceProcessor::should_drop(oop reference, ReferenceType type) const {
  const oop referent = reference_referent(reference);
  if (referent == NULL) {
    // Reference has been cleared, by a call to Reference.enqueue()
    // or Reference.clear() from the application, which means we
    // should drop the reference.
    return true;
  }
  // 注意每个 reference 关联的 ReferenceQueue 不一样。FinalReference 完全由 JVM 处理对用户不可见
  // 其他 Reference 类型可由用户自主决定
  // FinalReference 和 PhantomReference 用的是 zlivemap 中同一个标记位
  // 标记他们的 referent 的时候在 zlivemap 中用的是同一个标记位
  // Check if the referent is still alive, in which case we should
  // drop the reference.
  if (type == REF_PHANTOM) {
      // 如果 referent 因为 FinalReference 被标记 live，那么 PhantomReference 这里会返回 true
      // 本轮 gc 会把 PhantomReference drop 掉，等到执行完 referent 的 finaliser 方法
      // 下一轮 gc 再来处理 PhantomReference
    return ZBarrier::is_alive_barrier_on_phantom_oop(referent);
  } else {
      // StrongReference ，WeakReference，SoftReference 在 zlivemap 中用的一个标记位

      // 所以一个对象在 zlivemap 中用两位标记，一位用于表示 referent 对象是否是 FinalReference，PhantomReference 可达
      // 另一位表示 referent 对象是否是强可达，（StrongReference ，WeakReference，SoftReference）判断的同一位
      // 只要不是强可达，无论 referent 是不是因为 FinalReference 重新被标记，WeakReference 不管，都会加入 padding list
      // 即使 referent 因为 FinalReference 被标记 live，那么 WeakReference ,SoftReference 都会返回 false
      // 和 FinalReference 一起将在本轮 gc 中被处理，FinalReference 先被处理，WeakReference 和 SoftReference 后被处理
    return ZBarrier::is_alive_barrier_on_weak_oop(referent);
  }
}

void ZReferenceProcessor::keep_alive(oop reference, ReferenceType type) const {
  volatile oop* const p = reference_referent_addr(reference);
  if (type == REF_PHANTOM) {
    ZBarrier::keep_alive_barrier_on_phantom_oop_field(p);
  } else {
    ZBarrier::keep_alive_barrier_on_weak_oop_field(p);
  }
}

void ZReferenceProcessor::make_inactive(oop reference, ReferenceType type) const {
  if (type == REF_FINAL) {
    // Don't clear referent. It is needed by the Finalizer thread to make the call
    // to finalize(). A FinalReference is instead made inactive by self-looping the
    // next field. An application can't call FinalReference.enqueue(), so there is
    // no race to worry about when setting the next field.
    assert(reference_next(reference) == NULL, "Already inactive");
    reference_set_next(reference, reference);
  } else {
    // Clear referent
    reference_clear_referent(reference);
  }
}

void ZReferenceProcessor::discover(oop reference, ReferenceType type) {
  log_trace(gc, ref)("Discovered Reference: " PTR_FORMAT " (%s)", p2i(reference), reference_type_name(type));

  // Update statistics
  _discovered_count.get()[type]++;

  if (type == REF_FINAL) {
    // Mark referent (and its reachable subgraph) finalizable. This avoids
    // the problem of later having to mark those objects if the referent is
    // still final reachable during processing.
    volatile oop* const referent_addr = reference_referent_addr(reference);
    // 如果是 FinalReference 那么就需要对 referent 进行标记，视图改为 finalizable 表示只能通过 finaliz 方法才能访问到 referent 对象
    // 因为 referent 后续需要通过 finalizable 方法被访问，所以这里需要对它进行标记，不能回收
    ZBarrier::mark_barrier_on_oop_field(referent_addr, true /* finalizable */);
  }

  // Add reference to discovered list
  // 确保 reference 不在 _discovered_list 中，不能重复添加
  assert(reference_discovered(reference) == NULL, "Already discovered");
  oop* const list = _discovered_list.addr();
  // 头插法，reference->discovered = *list
  reference_set_discovered(reference, *list);
  // reference 变为 _discovered_list 的头部
  *list = reference;
}

bool ZReferenceProcessor::discover_reference(oop reference, ReferenceType type) {
  // 定义在 globals.hpp
  // 默认为 true，Tell whether the VM should register soft/weak/final/phantom
  // develop flags are settable / visible only during development and are constant in the PRODUCT version
  // -XX:+RegisterReferences -- develop flags 表示是否开启 Referenced 的处理
  // 和 ReferenceQueue 关联与否没关系
  if (!RegisterReferences) {
    // Reference processing disabled
    return false;
  }

  log_trace(gc, ref)("Encountered Reference: " PTR_FORMAT " (%s)", p2i(reference), reference_type_name(type));

  // Update statistics
  _encountered_count.get()[type]++;
  // true : 表示 referent 还存活（被强引用或者软引用关联），那么就不能放到 _discovered_list
  // false ： 表示 referent 不在存活，那么就需要把 reference 放入 _discovered_list
  if (!should_discover(reference, type)) {
    // Not discovered
    return false;
  }
    // 将 reference 插入到  _discovered_list 中（头插法）
    // 如果 reference 是一个 FinalReference ，那么还需要对其 referent 进行标记，本轮 gc 不能回收 referent
    // 因为 referent 需要保证在  finalizable 方法中被访问到
  discover(reference, type);

  // Discovered
  return true;
}

oop ZReferenceProcessor::drop(oop reference, ReferenceType type) {
  log_trace(gc, ref)("Dropped Reference: " PTR_FORMAT " (%s)", p2i(reference), reference_type_name(type));

  // Keep referent alive
  keep_alive(reference, type);

  // Unlink and return next in list
  const oop next = reference_discovered(reference);
  reference_set_discovered(reference, NULL);
  return next;
}

oop* ZReferenceProcessor::keep(oop reference, ReferenceType type) {
  log_trace(gc, ref)("Enqueued Reference: " PTR_FORMAT " (%s)", p2i(reference), reference_type_name(type));

  // Update statistics
  _enqueued_count.get()[type]++;

  // Make reference inactive
  // referent 置为 null
  make_inactive(reference, type);

  // Return next in list
  return reference_discovered_addr(reference);
}

void ZReferenceProcessor::work() {
  // Process discovered references
  // ZPerWork 类型的 addr 获取的是链表的头地址
  oop* const list = _discovered_list.addr();
  oop* p = list;

  while (*p != NULL) {
    const oop reference = *p;
    const ReferenceType type = reference_type(reference);
    // 如果该 reference 已经被应用程序处理过了 -> referent == NULL, 那么就不需要再被处理了，直接丢弃
    // 如果 referent 依然存活，那么也要丢弃，不能放入 _discovered_list 中
    if (should_drop(reference, type)) {
        // 如果 referent 是活跃的，则重新标记 referent 对象
        // 并将 reference 从 _discovered_list 中删除
      *p = drop(reference, type);
    } else {
        // 这里会调用 reference 的 clear 方法 -> referent 置为 null
        // 返回 reference 在 _discovered_list 中的下一个对象
      p = keep(reference, type);
    }
  }

  // Prepend discovered references to internal pending list（尾插法）
  if (*list != NULL) {
      // _pending_list.addr() 获取 _pending_list 的尾部地址（ZContend 类型 addr 的获取）
      // Atomic::xchg 替换成功，返回原来的内容
    *p = Atomic::xchg(_pending_list.addr(), *list);
    if (*p == NULL) {
      // First to prepend to list, record tail
      _pending_list_tail = p;
    }

    // Clear discovered list
    *list = NULL;
  }
}

bool ZReferenceProcessor::is_empty() const {
  ZPerWorkerConstIterator<oop> iter(&_discovered_list);
  for (const oop* list; iter.next(&list);) {
    if (*list != NULL) {
      return false;
    }
  }

  if (_pending_list.get() != NULL) {
    return false;
  }

  return true;
}

void ZReferenceProcessor::reset_statistics() {
  assert(is_empty(), "Should be empty");

  // Reset encountered
  ZPerWorkerIterator<Counters> iter_encountered(&_encountered_count);
  for (Counters* counters; iter_encountered.next(&counters);) {
    for (int i = REF_SOFT; i <= REF_PHANTOM; i++) {
      (*counters)[i] = 0;
    }
  }

  // Reset discovered
  ZPerWorkerIterator<Counters> iter_discovered(&_discovered_count);
  for (Counters* counters; iter_discovered.next(&counters);) {
    for (int i = REF_SOFT; i <= REF_PHANTOM; i++) {
      (*counters)[i] = 0;
    }
  }

  // Reset enqueued
  ZPerWorkerIterator<Counters> iter_enqueued(&_enqueued_count);
  for (Counters* counters; iter_enqueued.next(&counters);) {
    for (int i = REF_SOFT; i <= REF_PHANTOM; i++) {
      (*counters)[i] = 0;
    }
  }
}

void ZReferenceProcessor::collect_statistics() {
  Counters encountered = {};
  Counters discovered = {};
  Counters enqueued = {};

  // Sum encountered
  ZPerWorkerConstIterator<Counters> iter_encountered(&_encountered_count);
  for (const Counters* counters; iter_encountered.next(&counters);) {
    for (int i = REF_SOFT; i <= REF_PHANTOM; i++) {
      encountered[i] += (*counters)[i];
    }
  }

  // Sum discovered
  ZPerWorkerConstIterator<Counters> iter_discovered(&_discovered_count);
  for (const Counters* counters; iter_discovered.next(&counters);) {
    for (int i = REF_SOFT; i <= REF_PHANTOM; i++) {
      discovered[i] += (*counters)[i];
    }
  }

  // Sum enqueued
  ZPerWorkerConstIterator<Counters> iter_enqueued(&_enqueued_count);
  for (const Counters* counters; iter_enqueued.next(&counters);) {
    for (int i = REF_SOFT; i <= REF_PHANTOM; i++) {
      enqueued[i] += (*counters)[i];
    }
  }

  // Update statistics
  ZStatReferences::set_soft(encountered[REF_SOFT], discovered[REF_SOFT], enqueued[REF_SOFT]);
  ZStatReferences::set_weak(encountered[REF_WEAK], discovered[REF_WEAK], enqueued[REF_WEAK]);
  ZStatReferences::set_final(encountered[REF_FINAL], discovered[REF_FINAL], enqueued[REF_FINAL]);
  ZStatReferences::set_phantom(encountered[REF_PHANTOM], discovered[REF_PHANTOM], enqueued[REF_PHANTOM]);

  // Trace statistics
  const ReferenceProcessorStats stats(discovered[REF_SOFT],
                                      discovered[REF_WEAK],
                                      discovered[REF_FINAL],
                                      discovered[REF_PHANTOM]);
  ZTracer::tracer()->report_gc_reference_stats(stats);
}

class ZReferenceProcessorTask : public ZTask {
private:
  ZReferenceProcessor* const _reference_processor;

public:
  ZReferenceProcessorTask(ZReferenceProcessor* reference_processor) :
      ZTask("ZReferenceProcessorTask"),
      _reference_processor(reference_processor) {}

  virtual void work() {
    _reference_processor->work();
  }
};

void ZReferenceProcessor::process_references() {
  ZStatTimer timer(ZSubPhaseConcurrentReferencesProcess);

  // Process discovered lists
  ZReferenceProcessorTask task(this);
  // gc _workers 一起运行 ZReferenceProcessorTask
  _workers->run(&task);

  // Update SoftReference clock
  soft_reference_update_clock();

  // Collect, log and trace statistics
  collect_statistics();
}

void ZReferenceProcessor::enqueue_references() {
  ZStatTimer timer(ZSubPhaseConcurrentReferencesEnqueue);

  if (_pending_list.get() == NULL) {
    // Nothing to enqueue
    return;
  }

  {
    // Heap_lock protects external pending list
    MonitorLocker ml(Heap_lock);

    // Prepend internal pending list to external pending list
    *_pending_list_tail = Universe::swap_reference_pending_list(_pending_list.get());

    // Notify ReferenceHandler thread
    ml.notify_all();
  }

  // Reset internal pending list
  _pending_list.set(NULL);
  _pending_list_tail = _pending_list.addr();
}
