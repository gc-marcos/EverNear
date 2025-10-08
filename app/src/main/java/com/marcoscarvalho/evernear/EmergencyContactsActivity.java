package com.marcoscarvalho.evernear;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

public class EmergencyContactsActivity extends Activity {

    private static final String PREFS_NAME = "evernear_prefs";
    private static final String KEY_CONTACT_1 = "emergency_contact_1";
    private static final String KEY_CONTACT_2 = "emergency_contact_2";
    private static final String KEY_CONTACT_3 = "emergency_contact_3";
    private static final int REQUEST_READ_CONTACTS = 2001;
    private static final int REQUEST_PICK_CONTACT_SLOT_1 = 3001;
    private static final int REQUEST_PICK_CONTACT_SLOT_2 = 3002;
    private static final int REQUEST_PICK_CONTACT_SLOT_3 = 3003;

    private TextView contact1;
    private TextView contact2;
    private TextView contact3;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_emergency_contacts);

        contact1 = findViewById(R.id.contact_1);
        contact2 = findViewById(R.id.contact_2);
        contact3 = findViewById(R.id.contact_3);

        loadContacts();

        // Set click listeners to open contact picker
        contact1.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureContactsPermissionAndPick(REQUEST_PICK_CONTACT_SLOT_1);
            }
        });

        contact2.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureContactsPermissionAndPick(REQUEST_PICK_CONTACT_SLOT_2);
            }
        });

        contact3.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                ensureContactsPermissionAndPick(REQUEST_PICK_CONTACT_SLOT_3);
            }
        });
    }

    private void loadContacts() {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        String contact1Text = prefs.getString(KEY_CONTACT_1, "Contato 1");
        String contact2Text = prefs.getString(KEY_CONTACT_2, "Contato 2");
        String contact3Text = prefs.getString(KEY_CONTACT_3, "Contato 3");
        
        contact1.setText(contact1Text);
        contact2.setText(contact2Text);
        contact3.setText(contact3Text);
    }

    private void saveContact(int slot, String contactInfo) {
        SharedPreferences prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor editor = prefs.edit();
        
        String key;
        switch (slot) {
            case REQUEST_PICK_CONTACT_SLOT_1:
                key = KEY_CONTACT_1;
                break;
            case REQUEST_PICK_CONTACT_SLOT_2:
                key = KEY_CONTACT_2;
                break;
            case REQUEST_PICK_CONTACT_SLOT_3:
                key = KEY_CONTACT_3;
                break;
            default:
                return;
        }
        
        editor.putString(key, contactInfo);
        editor.apply();
    }

    private void ensureContactsPermissionAndPick(int requestCode) {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{android.Manifest.permission.READ_CONTACTS}, REQUEST_READ_CONTACTS + requestCode);
            return;
        }
        launchContactPicker(requestCode);
    }

    private void launchContactPicker(int requestCode) {
        Intent intent = new Intent(Intent.ACTION_PICK, ContactsContract.CommonDataKinds.Phone.CONTENT_URI);
        try {
            startActivityForResult(intent, requestCode);
        } catch (Exception e) {
            Toast.makeText(this, "Erro ao abrir contatos", Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "Permissão de contatos negada", Toast.LENGTH_SHORT).show();
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
                String contactInfo = displayName + "\n" + phoneNumber;
                
                if (requestCode == REQUEST_PICK_CONTACT_SLOT_1) {
                    contact1.setText(contactInfo);
                    saveContact(REQUEST_PICK_CONTACT_SLOT_1, contactInfo);
                } else if (requestCode == REQUEST_PICK_CONTACT_SLOT_2) {
                    contact2.setText(contactInfo);
                    saveContact(REQUEST_PICK_CONTACT_SLOT_2, contactInfo);
                } else if (requestCode == REQUEST_PICK_CONTACT_SLOT_3) {
                    contact3.setText(contactInfo);
                    saveContact(REQUEST_PICK_CONTACT_SLOT_3, contactInfo);
                }
            }
        } finally {
            if (cursor != null) cursor.close();
        }
    }
}