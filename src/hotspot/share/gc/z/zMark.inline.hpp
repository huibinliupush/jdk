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

#ifndef SHARE_GC_Z_ZMARK_INLINE_HPP
#define SHARE_GC_Z_ZMARK_INLINE_HPP

#include "gc/z/zMark.hpp"

#include "gc/z/zAddress.inline.hpp"
#include "gc/z/zMarkStack.inline.hpp"
#include "gc/z/zPage.inline.hpp"
#include "gc/z/zPageTable.inline.hpp"
#include "gc/z/zThreadLocalData.hpp"
#include "runtime/thread.hpp"
#include "utilities/debug.hpp"

// Marking before pushing helps reduce mark stack memory usage. However,
// we only mark before pushing in GC threads to avoid burdening Java threads
// with writing to, and potentially first having to clear, mark bitmaps.
//
// It's also worth noting that while marking an object can be done at any
// time in the marking phase, following an object can only be done after
// root processing has called ClassLoaderDataGraph::clear_claimed_marks(),
// since it otherwise would interact badly with claiming of CLDs.

template <bool gc_thread, bool follow, bool finalizable, bool publish>
inline void ZMark::mark_object(uintptr_t addr) {
  assert(ZAddress::is_marked(addr), "Should be marked");
  // 获取对象所在的 zpage
  ZPage* const page = _page_table->get(addr);
  // 判断是否是当前 gc 周期内分配的对象，如果是的话，应用线程在当前 gc 周期并发创建的对象
  // 已经被标记为活跃了，这里直接 return
  if (page->is_allocating()) {
    // Already implicitly marked
    return;
  }
  // 这里 gc 线程和应用线程是并发运行的,应用线程在标记阶段并发访问对象也可以通过读屏障来帮助标记
  const bool mark_before_push = gc_thread;
  bool inc_live = false;

  if (mark_before_push) {
    // Try mark object，
    // gc 线程标记根对象，如果根对象之前已经被标记则返回 false
    // 如果根对象之前没有被标记，则标记根对象并返回 true，inc_live 被设置为 true
    if (!page->mark_object(addr, finalizable, inc_live)) {
      // Already marked
      return;
    }
  } else {
    // Don't push if already marked
    // 应用线程这里判断对象是否已经被标记,如果已经标记则 return
    if (page->is_object_marked<finalizable>(addr)) {
      // Already marked
      return;
    }
  }

  // Push
  // 此时根对象已经被标记过了并且对象的地址视图也转换过了
  // 将标记过的根对象放入 gc 线程的本地标记栈中，后面并发标记的时候会根据标记栈中的对象开始遍历引用关系图
  // 每一个线程都会有一个本地标记栈（应用线程和 gc 线程）不同的线程可以 steal 任务从其他线程的标记栈中
  ZMarkThreadLocalStacks* const stacks = ZThreadLocalData::stacks(Thread::current());
  // 获取对象所在的标记条带
  ZMarkStripe* const stripe = _stripes.stripe_for_addr(addr);
  // !mark_before_push 用于初始化 ZMarkStackEntry 中的 field_mark 属性
  // mark_before_push = true 表示当前线程为 gc 线程，当前对象已经被标记过了，所以在后面的并发标记中不需要再次标记 field_mark = !mark_before_push = false
  // 在后面的 ZMark::mark_and_follow 函数中，根对象不用再标记了，直接标记它的 follow
  // mark_before_push = false 表示当前线程为应用线程，应用线程不会执行标记动作，只不过是把访问到的对象封装程 ZMarkStackEntry push 到标记栈中
  // 在后面的 ZMark::mark_and_follow 函数中，会对其进行标记，然后在标记它的 follow
  // mark_before_push 的语义是在将对象 push 到标记栈之前，是否已经标记了，gc 线程标记，应用线程不会标记
  ZMarkStackEntry entry(addr, !mark_before_push, inc_live, follow, finalizable);
  // 将待标记对象放入到 gc 线程本地标记栈中
  stacks->push(&_allocator, &_stripes, stripe, entry, publish);
}

#endif // SHARE_GC_Z_ZMARK_INLINE_HPP
