package com.telehost.app

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.telehost.app.databinding.ItemBotBinding

class BotAdapter(
    private var bots: List<BotItem>,
    private val onToggle: (BotItem) -> Unit,
    private val onLogs: (BotItem) -> Unit,
    private val onEdit: (BotItem) -> Unit,
    private val onDelete: (BotItem) -> Unit
) : RecyclerView.Adapter<BotAdapter.BotViewHolder>() {

    class BotViewHolder(val binding: ItemBotBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): BotViewHolder {
        val binding = ItemBotBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return BotViewHolder(binding)
    }

    override fun onBindViewHolder(holder: BotViewHolder, position: Int) {
        val bot = bots[position]
        val context = holder.itemView.context

        holder.binding.tvBotName.text = bot.name
        holder.binding.tvBotCommand.text = "> ${bot.command}"
        holder.binding.tvBotPath.text = bot.workingDir

        val isRunning = bot.status == "running"

        if (isRunning) {
            holder.binding.chipBotStatus.text = "🟢 Работает"
            holder.binding.chipBotStatus.setTextColor(ContextCompat.getColor(context, R.color.terminal_green))
            holder.binding.chipBotStatus.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.terminal_green))
            holder.binding.chipBotStatus.chipBackgroundColor = ColorStateList.valueOf(Color.parseColor("#1B2E1D"))

            holder.binding.btnToggleBot.text = "Остановить"
            holder.binding.btnToggleBot.setIconResource(R.drawable.ic_stop)
            holder.binding.btnToggleBot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.terminal_red))
        } else {
            holder.binding.chipBotStatus.text = "⚪ Остановлен"
            holder.binding.chipBotStatus.setTextColor(ContextCompat.getColor(context, R.color.on_surface_variant))
            holder.binding.chipBotStatus.chipStrokeColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.outline))
            holder.binding.chipBotStatus.chipBackgroundColor = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.card_surface))

            holder.binding.btnToggleBot.text = "Запустить"
            holder.binding.btnToggleBot.setIconResource(R.drawable.ic_play)
            holder.binding.btnToggleBot.backgroundTintList = ColorStateList.valueOf(ContextCompat.getColor(context, R.color.primary_container))
        }

        holder.binding.btnToggleBot.setOnClickListener { onToggle(bot) }
        holder.binding.btnBotLogs.setOnClickListener { onLogs(bot) }
        holder.binding.btnBotEdit.setOnClickListener { onEdit(bot) }
        holder.binding.btnBotDelete.setOnClickListener { onDelete(bot) }
    }

    override fun getItemCount(): Int = bots.size

    fun updateList(newBots: List<BotItem>) {
        bots = newBots
        notifyDataSetChanged()
    }
}
