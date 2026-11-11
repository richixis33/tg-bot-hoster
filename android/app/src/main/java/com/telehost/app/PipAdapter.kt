package com.telehost.app

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.telehost.app.databinding.ItemPipPackageBinding

data class PipPackage(
    val name: String,
    val version: String,
    var isInstalled: Boolean = true
)

class PipAdapter(
    private var packages: List<PipPackage>,
    private val onAction: (PipPackage) -> Unit
) : RecyclerView.Adapter<PipAdapter.PipViewHolder>() {

    class PipViewHolder(val binding: ItemPipPackageBinding) : RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): PipViewHolder {
        val binding = ItemPipPackageBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return PipViewHolder(binding)
    }

    override fun onBindViewHolder(holder: PipViewHolder, position: Int) {
        val pkg = packages[position]
        holder.binding.tvPkgName.text = pkg.name
        holder.binding.tvPkgDesc.text = "Версия: ${pkg.version} • ${if (pkg.isInstalled) "Установлен" else "Доступен"}"

        holder.binding.btnPkgAction.text = if (pkg.isInstalled) "Удалить" else "Скачать"
        holder.binding.btnPkgAction.setOnClickListener {
            onAction(pkg)
        }
    }

    override fun getItemCount(): Int = packages.size

    fun updateList(newList: List<PipPackage>) {
        packages = newList
        notifyDataSetChanged()
    }
}
