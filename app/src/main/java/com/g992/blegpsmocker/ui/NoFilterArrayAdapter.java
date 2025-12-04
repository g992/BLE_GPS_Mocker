package com.g992.blegpsmocker.ui;

import android.content.Context;
import android.widget.ArrayAdapter;
import android.widget.Filter;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class NoFilterArrayAdapter extends ArrayAdapter<String> {
    private final List<String> items;

    public NoFilterArrayAdapter(
            @NonNull Context context,
            int resource,
            @NonNull List<String> values
    ) {
        super(context, resource, new ArrayList<>(values));
        this.items = new ArrayList<>(values);
    }

    @Override
    public int getCount() {
        return items.size();
    }

    @Nullable
    @Override
    public String getItem(int position) {
        return items.get(position);
    }

    @NonNull
    @Override
    public Filter getFilter() {
        return new Filter() {
            @Override
            protected FilterResults performFiltering(CharSequence constraint) {
                FilterResults results = new FilterResults();
                results.count = items.size();
                results.values = items;
                return results;
            }

            @Override
            protected void publishResults(CharSequence constraint, FilterResults results) {
                notifyDataSetChanged();
            }

            @Override
            public CharSequence convertResultToString(Object resultValue) {
                return resultValue instanceof CharSequence
                        ? (CharSequence) resultValue
                        : super.convertResultToString(resultValue);
            }
        };
    }
}
