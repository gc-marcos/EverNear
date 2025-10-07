package com.marcoscarvalho.evernear;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.text.TextUtils;
import android.view.View;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class EmergencyContactsActivity extends Activity {

    private static final String PREFS_NAME = "evernear_prefs";
    private static final String KEY_NAME_1 = "emergency_name_1";
    private static final String KEY_PHONE_1 = "emergency_phone_1";
    private static final String KEY_NAME_2 = "emergency_name_2";
    private static final String KEY_PHONE_2 = "emergency_phone_2";
    private static final String KEY_NAME_3 = "emergency_name_3";
    private static final String KEY_PHONE_3 = "emergency_phone_3";

    private EditText name1;
    private EditText phone1;
    private EditText name2;
    private EditText phone2;
    private EditText name3;
    private EditText phone3;
    private ImageButton saveButton;
    private static final int REQUEST_READ_CONTACTS = 2001;
    private static final int REQUEST_PICK_CONTACT_SLOT_1 = 3001;
    private static final int REQUEST_PICK_CONTACT_SLOT_2 = 3002;
    private static final int REQUEST_PICK_CONTACT_SLOT_3 = 3003;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_contacts);

        name1 = findViewById(R.id.contact_name_1);
        phone1 = findViewById(R.id.contact_phone_1);
        name2 = findViewById(R.id.contact_name_2);
        phone2 = findViewById(R.id.contact_phone_2);
        name3 = findViewById(R.id.contact_name_3);
        phone3 = findViewById(R.id.contact_phone_3);
        saveButton = findViewById(R.id.save_contacts_button);

        loadContacts();

        saveButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                saveContacts();
            }
        });


        // Make fields act as pickers
        View.OnClickListener slot1Picker = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureContactsPermissionAndPick(REQUEST_PICK_CONTACT_SLOT_1);
            }
        };
        View.OnClickListener slot2Picker = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureContactsPermissionAndPick(REQUEST_PICK_CONTACT_SLOT_2);
            }
        };
        View.OnClickListener slot3Picker = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureContactsPermissionAndPick(REQUEST_PICK_CONTACT_SLOT_3);
            }
        };

        name1.setOnClickListener(slot1Picker);
        phone1.setOnClickListener(slot1Picker);
        name2.setOnClickListener(slot2Picker);
        phone2.setOnClickListener(slot2Picker);
        name3.setOnClickListener(slot3Picker);
        phone3.setOnClickListener(slot3Picker);
    }

    private void loadContacts() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        name1.setText(prefs.getString(KEY_NAME_1, ""));
        phone1.setText(prefs.getString(KEY_PHONE_1, ""));
        name2.setText(prefs.getString(KEY_NAME_2, ""));
        phone2.setText(prefs.getString(KEY_PHONE_2, ""));
        name3.setText(prefs.getString(KEY_NAME_3, ""));
        phone3.setText(prefs.getString(KEY_PHONE_3, ""));
    }

    private void saveContacts() {
        if (!isAnyContactFilled()) {
            Toast.makeText(this, R.string.emergency_contacts_empty_warning, Toast.LENGTH_SHORT).show();
            return;
        }

        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        editor.putString(KEY_NAME_1, name1.getText().toString().trim());
        editor.putString(KEY_PHONE_1, phone1.getText().toString().trim());
        editor.putString(KEY_NAME_2, name2.getText().toString().trim());
        editor.putString(KEY_PHONE_2, phone2.getText().toString().trim());
        editor.putString(KEY_NAME_3, name3.getText().toString().trim());
        editor.putString(KEY_PHONE_3, phone3.getText().toString().trim());
        editor.apply();

        Toast.makeText(this, R.string.emergency_contacts_saved, Toast.LENGTH_SHORT).show();
        finish();
    }

    private boolean isAnyContactFilled() {
        return !(TextUtils.isEmpty(name1.getText()) && TextUtils.isEmpty(phone1.getText()) &&
                 TextUtils.isEmpty(name2.getText()) && TextUtils.isEmpty(phone2.getText()) &&
                 TextUtils.isEmpty(name3.getText()) && TextUtils.isEmpty(phone3.getText()));
    }

    private void ensureContactsPermissionAndPick(int requestCode) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.READ_CONTACTS}, REQUEST_READ_CONTACTS + requestCode);
            // We encode the desired pick request into the permission request code by adding
            return;
        }
        launchContactPicker(requestCode);
    }

    private void launchContactPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(this, R.string.emergency_contacts_picker_error, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (grantResults == null || grantResults.length == 0) return;
        if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            int pickRequest;
            if (requestCode == REQUEST_READ_CONTACTS + REQUEST_PICK_CONTACT_SLOT_1) {
                pickRequest = REQUEST_PICK_CONTACT_SLOT_1;
            } else if (requestCode == REQUEST_READ_CONTACTS + REQUEST_PICK_CONTACT_SLOT_2) {
                pickRequest = REQUEST_PICK_CONTACT_SLOT_2;
            } else if (requestCode == REQUEST_READ_CONTACTS + REQUEST_PICK_CONTACT_SLOT_3) {
                pickRequest = REQUEST_PICK_CONTACT_SLOT_3;
            } else {
                return;
            }
            launchContactPicker(pickRequest);
        } else {
            Toast.makeText(this, R.string.emergency_contacts_permission_denied, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;
        Uri contactUri = data.getData();
        if (contactUri == null) return;

        String[] projection = new String[] {
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
        };
        Cursor cursor = null;
        try {
            cursor = getContentResolver().query(contactUri, projection, null, null, null);
            if (cursor != null && cursor.moveToFirst()) {
                String displayName = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME));
                String phoneNumber = cursor.getString(cursor.getColumnIndexOrThrow(ContactsContract.CommonDataKinds.Phone.NUMBER));
                if (requestCode == REQUEST_PICK_CONTACT_SLOT_1) {
                    name1.setText(displayName);
                    phone1.setText(phoneNumber);
                } else if (requestCode == REQUEST_PICK_CONTACT_SLOT_2) {
                    name2.setText(displayName);
                    phone2.setText(phoneNumber);
                } else if (requestCode == REQUEST_PICK_CONTACT_SLOT_3) {
                    name3.setText(displayName);
                    phone3.setText(phoneNumber);
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}


