package com.hotspot.controller.ui

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.hotspot.controller.databinding.ItemUrlRuleBinding
import com.hotspot.controller.model.UrlRule

class UrlRuleAdapter(
    private val onToggleRule: (ruleId: String, isEnabled: Boolean) -> Unit,
    private val onDeleteRule: (ruleId: String) -> Unit
) : RecyclerView.Adapter<UrlRuleAdapter.UrlRuleViewHolder>() {

    private val rules = mutableListOf<UrlRule>()

    fun submitList(newRules: List<UrlRule>) {
        rules.clear()
        rules.addAll(newRules)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): UrlRuleViewHolder {
        val binding = ItemUrlRuleBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return UrlRuleViewHolder(binding)
    }

    override fun onBindViewHolder(holder: UrlRuleViewHolder, position: Int) {
        holder.bind(rules[position])
    }

    override fun getItemCount(): Int = rules.size

    inner class UrlRuleViewHolder(private val binding: ItemUrlRuleBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(rule: UrlRule) {
            binding.tvRulePattern.text = rule.domainPattern
            binding.tvRuleCategory.text = rule.category

            binding.switchRuleEnabled.setOnCheckedChangeListener(null)
            binding.switchRuleEnabled.isChecked = rule.isEnabled
            binding.switchRuleEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleRule(rule.id, isChecked)
            }

            binding.btnDeleteRule.setOnClickListener {
                onDeleteRule(rule.id)
            }
        }
    }
}
