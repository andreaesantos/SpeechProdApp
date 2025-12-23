package dev.andrea.perroquet

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView

class InstructionPagerAdapter(private val instructionPages: List<InstructionActivity.InstructionPage>) : 
    RecyclerView.Adapter<InstructionPagerAdapter.InstructionViewHolder>() {
    
    class InstructionViewHolder(view: View, hasImage: Boolean) : RecyclerView.ViewHolder(view) {
        val textView: TextView = view.findViewById(R.id.instructionTextView)
        val imageView: ImageView? = if (hasImage) view.findViewById(R.id.instructionImageView) else null
    }
    
    override fun getItemViewType(position: Int): Int {
        return if (instructionPages[position].imageRes != null) 1 else 0
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): InstructionViewHolder {
        val layoutRes = when (viewType) {
            1 -> R.layout.instruction_page_with_image
            else -> R.layout.instruction_page_item
        }
        val view = LayoutInflater.from(parent.context)
            .inflate(layoutRes, parent, false)
        return InstructionViewHolder(view, viewType == 1)
    }
    
    override fun onBindViewHolder(holder: InstructionViewHolder, position: Int) {
        val page = instructionPages[position]
        holder.textView.text = page.text
        
        holder.imageView?.let { imageView ->
            if (page.imageRes != null) {
                imageView.setImageResource(page.imageRes)
                imageView.visibility = View.VISIBLE
            } else {
                imageView.visibility = View.GONE
            }
        }
    }
    
    override fun getItemCount(): Int = instructionPages.size
}
