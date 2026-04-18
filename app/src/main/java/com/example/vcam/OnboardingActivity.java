package com.example.vcam;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.recyclerview.widget.RecyclerView;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.button.MaterialButton;

/**
 * First-launch onboarding.  Minimal ViewPager2 pager backed by a static list of pages.
 */
public class OnboardingActivity extends AppCompatActivity {

    private static final int[][] PAGES = {
            {R.string.onboarding_title_1, R.string.onboarding_body_1},
            {R.string.onboarding_title_2, R.string.onboarding_body_2},
            {R.string.onboarding_title_3, R.string.onboarding_body_3}
    };

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_onboarding);

        ViewPager2 pager = findViewById(R.id.onboarding_pager);
        pager.setAdapter(new PageAdapter());

        MaterialButton next = findViewById(R.id.onboarding_next);
        MaterialButton skip = findViewById(R.id.onboarding_skip);

        next.setOnClickListener(v -> {
            int p = pager.getCurrentItem();
            if (p < PAGES.length - 1) {
                pager.setCurrentItem(p + 1);
            } else {
                finishOnboarding();
            }
        });
        skip.setOnClickListener(v -> finishOnboarding());

        pager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                next.setText(position == PAGES.length - 1
                        ? R.string.onboarding_done
                        : R.string.onboarding_next);
            }
        });
    }

    private void finishOnboarding() {
        SharedPreferences prefs = getSharedPreferences(VCAMApp.PREFS, MODE_PRIVATE);
        prefs.edit().putBoolean(VCAMApp.KEY_ONBOARDING_DONE, true).apply();
        finish();
    }

    private static class PageAdapter extends RecyclerView.Adapter<PageAdapter.VH> {
        @NonNull
        @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View v = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_onboarding_page, parent, false);
            return new VH(v);
        }

        @Override
        public void onBindViewHolder(@NonNull VH holder, int position) {
            holder.title.setText(PAGES[position][0]);
            holder.body.setText(PAGES[position][1]);
        }

        @Override
        public int getItemCount() {
            return PAGES.length;
        }

        static class VH extends RecyclerView.ViewHolder {
            final TextView title, body;
            VH(@NonNull View itemView) {
                super(itemView);
                title = itemView.findViewById(R.id.page_title);
                body = itemView.findViewById(R.id.page_body);
            }
        }
    }
}
