package com.marcoscarvalho.evernear;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

public class MainPagerAdapter extends RecyclerView.Adapter<MainPagerAdapter.ViewHolder> {
    
    private Context context;
    private String[] pageTitles = {"Frequência", "Localização", "Contatos"};
    
    public MainPagerAdapter(Context context) {
        this.context = context;
    }
    
    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.page_layout, parent, false);
        return new ViewHolder(view);
    }
    
    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        holder.titleText.setText(pageTitles[position]);
    }
    
    @Override
    public int getItemCount() {
        return pageTitles.length;
    }
    
    public static class ViewHolder extends RecyclerView.ViewHolder {
        TextView titleText;
        
        public ViewHolder(@NonNull View itemView) {
            super(itemView);
            titleText = itemView.findViewById(R.id.page_title);
        }
    }
}



