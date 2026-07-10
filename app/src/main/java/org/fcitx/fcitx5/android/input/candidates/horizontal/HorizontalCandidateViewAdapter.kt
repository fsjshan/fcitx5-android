/*
 * SPDX-License-Identifier: LGPL-2.1-or-later
 * SPDX-FileCopyrightText: Copyright 2021-2024 Fcitx5 for Android Contributors
 */

package org.fcitx.fcitx5.android.input.candidates.horizontal

import android.view.ViewGroup
import androidx.annotation.CallSuper
import androidx.recyclerview.widget.RecyclerView
import com.google.android.flexbox.FlexboxLayoutManager
import org.fcitx.fcitx5.android.data.theme.Theme
import org.fcitx.fcitx5.android.input.candidates.CandidateItemUi
import org.fcitx.fcitx5.android.input.candidates.CandidateViewHolder
import splitties.dimensions.dp
import splitties.views.dsl.core.matchParent
import splitties.views.dsl.core.wrapContent
import splitties.views.setPaddingDp

open class HorizontalCandidateViewAdapter(val theme: Theme) :
    RecyclerView.Adapter<CandidateViewHolder>() {

    var candidates: Array<String> = arrayOf()
        private set

    var total = -1
        private set

    fun updateCandidates(data: Array<String>, total: Int) {
        val old = this.candidates
        this.candidates = data
        this.total = total
        // 增量更新：避免 notifyDataSetChanged 触发所有 item 全量重绘
        // AutoScaleTextView.setText 会调 requestLayout+invalidate，批量重绘开销大
        val oldSize = old.size
        val newSize = data.size
        val commonSize = minOf(oldSize, newSize)
        // 更新内容变化的位置（逐项比对，跳过未变化的 item）
        for (i in 0 until commonSize) {
            if (old[i] != data[i]) notifyItemChanged(i)
        }
        when {
            newSize > oldSize -> notifyItemRangeInserted(oldSize, newSize - oldSize)
            newSize < oldSize -> notifyItemRangeRemoved(newSize, oldSize - newSize)
        }
    }

    override fun getItemCount() = candidates.size

    @CallSuper
    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): CandidateViewHolder {
        val ui = CandidateItemUi(parent.context, theme)
        ui.root.apply {
            minimumWidth = dp(40)
            setPaddingDp(10, 0, 10, 0)
            layoutParams = FlexboxLayoutManager.LayoutParams(wrapContent, matchParent)
        }
        return CandidateViewHolder(ui)
    }

    @CallSuper
    override fun onBindViewHolder(holder: CandidateViewHolder, position: Int) {
        val text = candidates[position]
        holder.ui.text.text = text
        holder.text = text
        holder.idx = position
    }

}
