package com.yourapp.stavelectroset.feature.news.consumers;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.android.material.bottomsheet.BottomSheetDialogFragment;
import com.yourapp.stavelectroset.R;

public class ConsumersSheetDialogFragment extends BottomSheetDialogFragment {

    public enum Action { MAP, REPAIRS }

    public interface Listener {
        void onConsumersActionSelected(Action action);
    }

    private Listener listener;

    public void setListener(Listener listener) {
        this.listener = listener;
    }

    @Nullable @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View v = inflater.inflate(R.layout.bottomsheet_consumers, container, false);

        v.findViewById(R.id.action_map).setOnClickListener(view -> {
            if (listener != null) listener.onConsumersActionSelected(Action.MAP);
            dismiss();
        });

        v.findViewById(R.id.action_repairs).setOnClickListener(view -> {
            if (listener != null) listener.onConsumersActionSelected(Action.REPAIRS);
            dismiss();
        });

        return v;
    }
}
