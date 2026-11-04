package com.qoder.cmcchook;

import android.graphics.drawable.GradientDrawable;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

final class LogAdapter extends RecyclerView.Adapter<LogAdapter.Holder> {
    private final List<LogEntry> items = new ArrayList<>();
    private final Set<String> expandedKeys = new HashSet<>();

    void submit(List<LogEntry> entries) {
        items.clear();
        items.addAll(entries);
        for (LogEntry entry : items) {
            entry.expanded = expandedKeys.contains(entry.key());
        }
        notifyDataSetChanged();
    }

    void clear() {
        items.clear();
        expandedKeys.clear();
        notifyDataSetChanged();
    }

    LogEntry getItem(int position) {
        return items.get(position);
    }

    int size() {
        return items.size();
    }

    @NonNull
    @Override
    public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_log, parent, false);
        return new Holder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull Holder holder, int position) {
        LogEntry entry = items.get(position);
        holder.tagText.setText(entry.tag);
        holder.tagText.setBackground(tagBackground(holder.itemView, entry.tag));
        holder.timeText.setText(entry.formatTime());
        holder.summaryText.setText(entry.summary);
        holder.expandText.setText(entry.expanded ? R.string.collapse : R.string.expand);
        holder.detailText.setVisibility(entry.expanded ? View.VISIBLE : View.GONE);
        holder.detailText.setText(entry.expanded ? entry.detail : "");
        holder.itemView.setOnClickListener(v -> {
            int current = holder.getAdapterPosition();
            if (current == RecyclerView.NO_POSITION) {
                return;
            }
            LogEntry item = items.get(current);
            item.expanded = !item.expanded;
            if (item.expanded) {
                expandedKeys.add(item.key());
            } else {
                expandedKeys.remove(item.key());
            }
            notifyItemChanged(current);
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    private GradientDrawable tagBackground(View view, String tag) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setShape(GradientDrawable.RECTANGLE);
        float density = view.getResources().getDisplayMetrics().density;
        drawable.setCornerRadius(4f * density);
        drawable.setColor(tagColor(view, tag));
        return drawable;
    }

    private int tagColor(View view, String tag) {
        if (tag == null) {
            return view.getResources().getColor(R.color.tag_system);
        }
        if (tag.startsWith("H5")) {
            return view.getResources().getColor(R.color.tag_h5);
        }
        if (tag.startsWith("HTTP") || tag.startsWith("OKHTTP")) {
            return view.getResources().getColor(R.color.tag_http);
        }
        if (tag.startsWith("ACTIVITY") || tag.startsWith("WEBVIEW")
                || tag.startsWith("UI") || tag.startsWith("CLICK")) {
            return view.getResources().getColor(R.color.tag_ui);
        }
        if (tag.startsWith("ERROR")) {
            return view.getResources().getColor(R.color.tag_error);
        }
        return view.getResources().getColor(R.color.tag_system);
    }

    static final class Holder extends RecyclerView.ViewHolder {
        final TextView tagText;
        final TextView timeText;
        final TextView summaryText;
        final TextView expandText;
        final TextView detailText;

        Holder(@NonNull View itemView) {
            super(itemView);
            tagText = itemView.findViewById(R.id.tagText);
            timeText = itemView.findViewById(R.id.timeText);
            summaryText = itemView.findViewById(R.id.summaryText);
            expandText = itemView.findViewById(R.id.expandText);
            detailText = itemView.findViewById(R.id.detailText);
            detailText.setTextColor(androidx.core.content.ContextCompat.getColor(
                    itemView.getContext(), R.color.text_primary));
        }
    }
}
