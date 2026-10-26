package com.example.trojan_rideshare;

import android.content.Intent;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.firestore.DocumentSnapshot;
import com.google.firebase.firestore.FirebaseFirestore;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DateFormat;
import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/** Balance UI over CostSplitManager; existing screens and navigation stay unchanged. */
public class BalanceActivity extends AppCompatActivity {
    private CostSplitManager manager;
    private User user;
    private final List<Expense> expenses = new ArrayList<>();
    private final List<User> people = new ArrayList<>();
    private Spinner personFilter;
    private MaterialCheckBox settledOnly;
    private TextView message;
    private boolean busy;
    private int loadVersion;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_balance);
        manager = new CostSplitManager();
        message = findViewById(R.id.balanceMessage);
        personFilter = findViewById(R.id.balancePersonFilter);
        settledOnly = findViewById(R.id.balanceSettledOnly);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.balanceRoot), (view, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.ime());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return insets;
        });
        findViewById(R.id.balanceRefresh).setOnClickListener(v -> {
            if (!busy) refresh();
        });
        findViewById(R.id.balanceAdd).setOnClickListener(v -> {
            if (!busy && user != null) editExpense(null);
        });
        findViewById(R.id.balanceSplit).setOnClickListener(v -> {
            if (!busy && user != null) chooseRide();
        });
        findViewById(R.id.balanceSimplify).setOnClickListener(v -> {
            if (!busy && user != null) showSimplifiedTransfers();
        });
        findViewById(R.id.balanceBack).setOnClickListener(v -> {
            if (user == null) {
                startActivity(new Intent(this, LoginActivity.class));
            } else if (isTaskRoot()) {
                startActivity(new Intent(this, MainActivity.class));
            }
            finish();
        });
        settledOnly.setOnCheckedChangeListener((button, checked) -> renderExpenses());
        personFilter.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                renderExpenses();
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!busy) refresh();
    }

    private boolean active() {
        User current = AuthManager.getInstance().getCurrentUser();
        return !isFinishing() && !isDestroyed() && user != null
                && current != null && user.getId().equals(current.getId());
    }

    private void setBusy(boolean loading) {
        busy = loading;
        findViewById(R.id.balanceProgress).setVisibility(loading ? View.VISIBLE : View.GONE);
        for (int id : new int[]{R.id.balanceRefresh, R.id.balanceAdd, R.id.balanceSplit,
                R.id.balanceSimplify, R.id.balancePersonFilter, R.id.balanceSettledOnly}) {
            findViewById(id).setEnabled(!loading && user != null);
        }
    }

    private void refresh() {
        user = AuthManager.getInstance().getCurrentUser();
        int version = ++loadVersion;
        findViewById(R.id.balanceSummary).setVisibility(View.GONE);
        findViewById(R.id.balanceContent).setVisibility(View.GONE);
        MaterialButton back = findViewById(R.id.balanceBack);
        back.setText(user == null ? R.string.action_sign_in : R.string.balance_back);
        if (user == null) {
            expenses.clear();
            people.clear();
            setBusy(false);
            message.setText(R.string.balance_sign_in);
            return;
        }
        String selected = selectedPersonId();
        setBusy(true);
        message.setText(R.string.balance_loading);
        manager.getExpenses(user, loaded -> {
            if (!active() || version != loadVersion) return;
            setBusy(false);
            if (loaded == null) {
                message.setText(R.string.balance_error);
                return;
            }
            expenses.clear();
            expenses.addAll(loaded);
            populatePeople(selected);
            renderBalances();
            renderExpenses();
            findViewById(R.id.balanceSummary).setVisibility(View.VISIBLE);
            findViewById(R.id.balanceContent).setVisibility(View.VISIBLE);
            message.setText(loaded.isEmpty() ? getString(R.string.balance_empty) : "");
        });
    }

    private String selectedPersonId() {
        int index = personFilter.getSelectedItemPosition() - 1;
        return index >= 0 && index < people.size() ? people.get(index).getId() : null;
    }

    private void populatePeople(String selected) {
        Map<String, User> unique = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            User other = expense.getPayer().getId().equals(user.getId())
                    ? expense.getPayee() : expense.getPayer();
            unique.put(other.getId(), other);
        }
        people.clear();
        people.addAll(unique.values());
        List<String> names = new ArrayList<>();
        names.add(getString(R.string.balance_all_users));
        int selection = 0;
        for (User person : people) {
            names.add(name(person) + " (" + person.getEmail() + ")");
            if (person.getId().equals(selected)) selection = names.size() - 1;
        }
        setOptions(personFilter, names);
        personFilter.setSelection(selection);
    }

    private void renderBalances() {
        BigDecimal owed = BigDecimal.ZERO;
        BigDecimal receive = BigDecimal.ZERO;
        // Keep payer -> payee directions separate, including reciprocal obligations.
        Map<String, Map<String, BigDecimal>> pairs = new LinkedHashMap<>();
        Map<String, User> users = new LinkedHashMap<>();
        for (Expense expense : expenses) {
            users.put(expense.getPayer().getId(), expense.getPayer());
            users.put(expense.getPayee().getId(), expense.getPayee());
            if (expense.isPaid() || expense.getAmount() == 0) continue;
            BigDecimal amount = BigDecimal.valueOf(expense.getAmount());
            if (expense.getPayer().getId().equals(user.getId())) owed = owed.add(amount);
            if (expense.getPayee().getId().equals(user.getId())) receive = receive.add(amount);
            Map<String, BigDecimal> outgoing = pairs.computeIfAbsent(
                    expense.getPayer().getId(), key -> new LinkedHashMap<>());
            String payee = expense.getPayee().getId();
            outgoing.put(payee, outgoing.getOrDefault(payee, BigDecimal.ZERO).add(amount));
        }
        ((TextView) findViewById(R.id.balanceOwed)).setText(money(owed));
        ((TextView) findViewById(R.id.balanceReceive)).setText(money(receive));
        LinearLayout container = findViewById(R.id.balancePairs);
        container.removeAllViews();
        for (Map.Entry<String, Map<String, BigDecimal>> payer : pairs.entrySet()) {
            for (Map.Entry<String, BigDecimal> payee : payer.getValue().entrySet()) {
                container.addView(text(pair(users.get(payer.getKey()), users.get(payee.getKey()), payee.getValue()), 16));
            }
        }
        if (pairs.isEmpty()) container.addView(text(getString(R.string.balance_no_debts), 16));
    }

    private void renderExpenses() {
        LinearLayout container = findViewById(R.id.balanceExpenses);
        container.removeAllViews();
        String other = selectedPersonId();
        for (Expense expense : expenses) {
            if (settledOnly.isChecked() && !expense.isPaid()) continue;
            if (other != null && !other.equals(expense.getPayer().getId())
                    && !other.equals(expense.getPayee().getId())) continue;
            MaterialCardView card = new MaterialCardView(this);
            LinearLayout.LayoutParams cardParams = new LinearLayout.LayoutParams(-1, -2);
            cardParams.setMargins(0, dp(12), 0, dp(4));
            card.setLayoutParams(cardParams);
            card.setRadius(dp(16));
            card.setCardElevation(dp(4));
            card.setStrokeWidth(dp(1.5f));
            card.setStrokeColor(ContextCompat.getColor(this, R.color.usc_gold));
            LinearLayout body = column();
            body.setPadding(dp(20), dp(12), dp(20), dp(12));
            body.addView(text(pair(expense.getPayer(), expense.getPayee(),
                    BigDecimal.valueOf(expense.getAmount())), 18));
            body.addView(text(getString(R.string.balance_expense_details,
                    getString(expense.isPaid() ? R.string.balance_paid : R.string.balance_unpaid),
                    DateFormat.getDateInstance(DateFormat.MEDIUM).format(expense.getCreatedAt())), 14));
            if (!expense.isPaid()) {
                body.addView(action(R.string.balance_record, () -> recordPayment(expense)));
            }
            body.addView(action(R.string.balance_edit, () -> editExpense(expense)));
            body.addView(action(R.string.balance_delete, () -> deleteExpense(expense)));
            card.addView(body);
            container.addView(card);
        }
        if (container.getChildCount() == 0) {
            container.addView(text(getString(expenses.isEmpty()
                    ? R.string.balance_empty : R.string.balance_no_matches), 16));
        }
    }

    private void editExpense(Expense expense) {
        LinearLayout form = dialogColumn();
        TextInputEditText email = null;
        Spinner direction = null;
        if (expense == null) {
            email = input(form, getString(R.string.balance_email), InputType.TYPE_CLASS_TEXT
                    | InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS);
            direction = new Spinner(this);
            setOptions(direction, java.util.Arrays.asList(getString(R.string.balance_direction_owe),
                    getString(R.string.balance_direction_receive)));
            form.addView(direction);
        }
        TextInputEditText amount = input(form, getString(R.string.balance_amount),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        if (expense != null) amount.setText(BigDecimal.valueOf(expense.getAmount()).toPlainString());
        TextInputEditText otherEmail = email;
        Spinner owedDirection = direction;
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(expense == null ? R.string.balance_add : R.string.balance_edit)
                .setView(scroll).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.balance_save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            Double value = parseAmount(amount);
            if (value == null) return;
            if (expense != null) {
                change(callback -> manager.editExpense(expense, value, callback), dialog,
                        R.string.balance_change_error, 0);
                return;
            }
            String address = otherEmail.getText() == null ? "" : otherEmail.getText().toString().trim();
            if (address.isEmpty() || address.equalsIgnoreCase(user.getEmail())) {
                otherEmail.setError(getString(R.string.balance_user_error));
                return;
            }
            boolean iOwe = owedDirection.getSelectedItemPosition() == 0;
            setBusy(true);
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
            FirebaseFirestore.getInstance().collection("users").whereEqualTo("email", address).limit(1).get()
                    .addOnSuccessListener(result -> {
                        if (!active()) return;
                        setBusy(false);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        if (result.isEmpty()) {
                            otherEmail.setError(getString(R.string.balance_user_error));
                            return;
                        }
                        User other = profile(result.getDocuments().get(0));
                        if (other.getId().equals(user.getId())) {
                            otherEmail.setError(getString(R.string.balance_user_error));
                            return;
                        }
                        Expense created = new Expense(value, iOwe ? user : other, iOwe ? other : user);
                        change(callback -> manager.createExpense(created, callback), dialog,
                                R.string.balance_change_error, 0);
                    }).addOnFailureListener(error -> {
                        if (!active()) return;
                        setBusy(false);
                        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
                        otherEmail.setError(getString(R.string.balance_user_lookup_error));
                    });
        }));
        dialog.show();
    }

    private void deleteExpense(Expense expense) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.balance_delete)
                .setMessage(R.string.balance_delete_confirmation)
                .setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.balance_delete, (dialog, which) -> {
                    if (!busy) change(callback -> manager.deleteExpense(expense, callback), null,
                            R.string.balance_change_error, 0);
                }).show();
    }

    private void recordPayment(Expense expense) {
        new MaterialAlertDialogBuilder(this).setTitle(R.string.balance_record)
                .setMessage(R.string.balance_payment_explanation)
                .setNegativeButton(R.string.action_cancel, null)
                .setNeutralButton(R.string.balance_payment_failed, (dialog, which) -> {
                    if (!busy) change(callback -> manager.recordPayment(expense, false, callback), null,
                            R.string.balance_change_error, R.string.balance_payment_failure);
                })
                .setPositiveButton(R.string.balance_payment_succeeded, (dialog, which) -> {
                    if (!busy) change(callback -> manager.recordPayment(expense, true, callback), null,
                            R.string.balance_change_error, R.string.balance_payment_success);
                }).show();
    }

    private void change(Consumer<Consumer<Boolean>> operation, AlertDialog dialog, int error, int successMessage) {
        setBusy(true);
        if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);
        operation.accept(success -> {
            if (!active()) return;
            setBusy(false);
            if (dialog != null) dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);
            if (success) {
                if (dialog != null) dialog.dismiss();
                if (successMessage != 0) Toast.makeText(this, successMessage, Toast.LENGTH_LONG).show();
                refresh();
            } else {
                Toast.makeText(this, error, Toast.LENGTH_LONG).show();
            }
        });
    }

    private void showSimplifiedTransfers() {
        setBusy(true);
        manager.simplifyDebts(user, transfers -> {
            if (!active()) return;
            setBusy(false);
            if (transfers == null) {
                Toast.makeText(this, R.string.balance_error, Toast.LENGTH_LONG).show();
                return;
            }
            StringBuilder content = new StringBuilder(getString(R.string.balance_simplified_note));
            if (transfers.isEmpty()) content.append("\n\n").append(getString(R.string.balance_no_debts));
            for (Expense transfer : transfers) {
                content.append("\n\n").append(pair(transfer.getPayer(), transfer.getPayee(),
                        BigDecimal.valueOf(transfer.getAmount())));
            }
            new MaterialAlertDialogBuilder(this).setTitle(R.string.balance_simplified_title)
                    .setMessage(content).setPositiveButton(android.R.string.ok, null).show();
        });
    }

    private void chooseRide() {
        setBusy(true);
        FirebaseFirestore.getInstance().collection("rides").whereEqualTo("organizer.id", user.getId()).get()
                .addOnSuccessListener(result -> {
                    if (!active()) return;
                    setBusy(false);
                    List<Ride> rides = new ArrayList<>();
                    List<String> titles = new ArrayList<>();
                    try {
                        for (DocumentSnapshot snapshot : result.getDocuments()) {
                            if (!Status.COMPLETED.name().equals(snapshot.getString("rideStatus"))) continue;
                            Ride ride = RideManager.toRide(snapshot);
                            rides.add(ride);
                            titles.add(ride.getTitle() + " • " + DateFormat.getDateInstance().format(ride.getDepartureTime()));
                        }
                    } catch (Exception error) {
                        Toast.makeText(this, R.string.balance_rides_error, Toast.LENGTH_LONG).show();
                        return;
                    }
                    if (rides.isEmpty()) {
                        Toast.makeText(this, R.string.balance_no_rides, Toast.LENGTH_LONG).show();
                        return;
                    }
                    new MaterialAlertDialogBuilder(this).setTitle(R.string.balance_split)
                            .setItems(titles.toArray(new String[0]), (dialog, index) -> loadRidePeople(rides.get(index)))
                            .setNegativeButton(R.string.action_cancel, null).show();
                }).addOnFailureListener(error -> {
                    if (!active()) return;
                    setBusy(false);
                    Toast.makeText(this, R.string.balance_rides_error, Toast.LENGTH_LONG).show();
                });
    }

    private void loadRidePeople(Ride ride) {
        if (busy) return;
        setBusy(true);
        List<com.google.android.gms.tasks.Task<DocumentSnapshot>> requests = new ArrayList<>();
        for (String id : ride.getRiders()) {
            requests.add(FirebaseFirestore.getInstance().collection("users").document(id).get());
        }
        com.google.android.gms.tasks.Tasks.whenAllSuccess(requests).addOnSuccessListener(results -> {
            if (!active()) return;
            setBusy(false);
            Map<String, User> riders = new LinkedHashMap<>();
            for (Object result : results) {
                DocumentSnapshot snapshot = (DocumentSnapshot) result;
                if (!snapshot.exists()) {
                    Toast.makeText(this, R.string.balance_split_error, Toast.LENGTH_LONG).show();
                    return;
                }
                riders.put(snapshot.getId(), profile(snapshot));
            }
            splitRide(ride, riders);
        }).addOnFailureListener(error -> {
            if (!active()) return;
            setBusy(false);
            Toast.makeText(this, R.string.balance_rides_error, Toast.LENGTH_LONG).show();
        });
    }

    private void splitRide(Ride ride, Map<String, User> riders) {
        LinearLayout form = dialogColumn();
        form.addView(text(ride.getTitle(), 18));
        TextInputEditText total = input(form, getString(R.string.balance_actual_total),
                InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL);
        Spinner mode = new Spinner(this);
        setOptions(mode, java.util.Arrays.asList(getString(R.string.balance_split_mode_equal),
                getString(R.string.balance_split_mode_custom)));
        form.addView(mode);
        LinearLayout custom = column();
        Map<String, TextInputEditText> amounts = new LinkedHashMap<>();
        for (String id : ride.getRiders()) {
            amounts.put(id, input(custom, getString(R.string.balance_share, name(riders.get(id))),
                    InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_DECIMAL));
        }
        custom.setVisibility(View.GONE);
        form.addView(custom);
        mode.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                custom.setVisibility(position == 1 ? View.VISIBLE : View.GONE);
            }
            @Override
            public void onNothingSelected(AdapterView<?> parent) { }
        });
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        scroll.addView(form);
        AlertDialog dialog = new MaterialAlertDialogBuilder(this).setTitle(R.string.balance_split)
                .setView(scroll).setNegativeButton(R.string.action_cancel, null)
                .setPositiveButton(R.string.balance_save, null).create();
        dialog.setOnShowListener(ignored -> dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (busy) return;
            Double cost = parseAmount(total);
            if (cost == null) return;
            if (mode.getSelectedItemPosition() == 0) {
                change(callback -> manager.divideCost(ride, cost, CostSplitMode.EQUAL, callback), dialog,
                        R.string.balance_split_error, R.string.balance_split_saved);
                return;
            }
            Map<String, Double> shares = new LinkedHashMap<>();
            BigDecimal sum = BigDecimal.ZERO;
            for (Map.Entry<String, TextInputEditText> item : amounts.entrySet()) {
                Double amount = parseAmount(item.getValue());
                if (amount == null) return;
                shares.put(item.getKey(), amount);
                sum = sum.add(BigDecimal.valueOf(amount));
            }
            if (sum.compareTo(BigDecimal.valueOf(cost)) != 0) {
                total.setError(getString(R.string.balance_split_error));
                return;
            }
            change(callback -> manager.divideCost(ride, cost, CostSplitMode.CUSTOM, shares, callback), dialog,
                    R.string.balance_split_error, R.string.balance_split_saved);
        }));
        dialog.show();
    }

    private Double parseAmount(TextInputEditText input) {
        try {
            String value = input.getText() == null ? "" : input.getText().toString().trim();
            BigDecimal amount = new BigDecimal(value).setScale(2, RoundingMode.UNNECESSARY);
            if (amount.signum() < 0) throw new NumberFormatException();
            amount.movePointRight(2).longValueExact();
            double result = amount.doubleValue();
            if (BigDecimal.valueOf(result).compareTo(amount) != 0) throw new NumberFormatException();
            input.setError(null);
            return result;
        } catch (NumberFormatException | ArithmeticException error) {
            input.setError(getString(R.string.balance_amount_error));
            return null;
        }
    }

    private User profile(DocumentSnapshot snapshot) {
        return new User(snapshot.getId(), snapshot.getString("email"), "", snapshot.getString("fullName"),
                snapshot.getString("nickname"), 0, 0);
    }

    private String name(User person) {
        if (person.getId().equals(user.getId())) return getString(R.string.balance_you);
        if (person.getNickname() != null && !person.getNickname().trim().isEmpty()) return person.getNickname();
        if (person.getFullName() != null && !person.getFullName().trim().isEmpty()) return person.getFullName();
        return person.getEmail() == null ? person.getId() : person.getEmail();
    }

    private String pair(User payer, User payee, BigDecimal amount) {
        if (payer.getId().equals(user.getId())) {
            return getString(R.string.balance_you_owe, name(payee), money(amount));
        }
        if (payee.getId().equals(user.getId())) {
            return getString(R.string.balance_owes_you, name(payer), money(amount));
        }
        return getString(R.string.balance_pair, name(payer), name(payee), money(amount));
    }

    private String money(BigDecimal amount) {
        return NumberFormat.getCurrencyInstance(Locale.US).format(amount);
    }

    private int dp(float value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private LinearLayout column() {
        LinearLayout view = new LinearLayout(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        view.setOrientation(LinearLayout.VERTICAL);
        return view;
    }

    private LinearLayout dialogColumn() {
        LinearLayout form = column();
        form.setPadding(dp(24), dp(8), dp(24), dp(8));
        return form;
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        view.setText(value);
        view.setTextSize(size);
        view.setPadding(0, dp(8), 0, dp(8));
        return view;
    }

    private TextInputEditText input(LinearLayout form, String hint, int type) {
        TextInputLayout wrapper = new TextInputLayout(this, null,
                com.google.android.material.R.attr.textInputOutlinedStyle);
        wrapper.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        wrapper.setHint(hint);
        wrapper.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        TextInputEditText input = new TextInputEditText(wrapper.getContext());
        input.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        input.setInputType(type);
        input.setSingleLine(true);
        wrapper.addView(input);
        form.addView(wrapper);
        return input;
    }

    private MaterialButton action(int label, Runnable onClick) {
        MaterialButton button = new MaterialButton(this);
        button.setLayoutParams(new LinearLayout.LayoutParams(-1, -2));
        button.setText(label);
        button.setBackgroundTintList(ColorStateList.valueOf(ContextCompat.getColor(this, R.color.usc_cardinal)));
        button.setTextColor(ContextCompat.getColor(this, R.color.usc_gold));
        button.setOnClickListener(v -> {
            if (!busy && active()) onClick.run();
        });
        return button;
    }

    private void setOptions(Spinner spinner, List<String> values) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
    }
}
