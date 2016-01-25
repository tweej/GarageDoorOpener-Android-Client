package teej.garagedooropenerclient;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateExpiredException;
import java.security.cert.CertificateFactory;
import java.security.cert.CertificateNotYetValidException;
import java.security.cert.X509Certificate;
import java.text.DateFormat;
import java.util.regex.Pattern;


public class SettingsActivity extends Activity {

    private static final Pattern IP_ADDRESS
            = Pattern.compile(
            "((25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9])\\.(25[0-5]|2[0-4]"
                    + "[0-9]|[0-1][0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1]"
                    + "[0-9]{2}|[1-9][0-9]|[1-9]|0)\\.(25[0-5]|2[0-4][0-9]|[0-1][0-9]{2}"
                    + "|[1-9][0-9]|[0-9]))");

    // Widgets
    private EditText ipAddress;
    private EditText portNumber;
    private TextView md5Fingerprint;
    private TextView certInfo;

    private SharedPreferences.Editor editor;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.settings);

        ipAddress = (EditText) findViewById(R.id.ServerIPAddress);
        if(ipAddress == null) {
            Log.wtf("MyApp", "Could not find ServerIPAddress View");
        }

        portNumber = (EditText) findViewById(R.id.ServerPortNumber);
        if(portNumber == null) {
            Log.wtf("MyApp", "Could not find ServerPortNumber View");
        }

        md5Fingerprint = (TextView) findViewById(R.id.MD5Fingerprint);
        if(md5Fingerprint == null) {
            Log.wtf("MyApp", "Could not find MD5Fingerprint View");
        }

        certInfo = (TextView) findViewById(R.id.CertInfo);
        if(certInfo == null) {
            Log.wtf("MyApp", "Could not find CertInfo View");
        }


        // Read from Shared Preferences
        SharedPreferences sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.preferences_file_key), Context.MODE_PRIVATE);
        ipAddress.setText(sharedPref.getString("IP", ""));
        portNumber.setText(sharedPref.getString("PORT", ""));

        String EncodedCert = sharedPref.getString("CERT", getString(R.string.empty_fingerprint));
        if(!EncodedCert.equals(getString(R.string.empty_fingerprint))) {
            try {
                byte[] decoded = Base64.decode(EncodedCert, Base64.NO_WRAP);
                InputStream is = new ByteArrayInputStream(decoded);
                loadCertificate(is);

                // Compute and display the MD5 fingerprint of the certificate
                md5Fingerprint.setText(generateMD5Fingerprint(decoded));
                md5Fingerprint.setTypeface(null, Typeface.NORMAL);
            } catch (CertificateException e) {
                Log.wtf("MyApp", e.toString());
                Toast.makeText(this,
                        "Error loading certificate! Please report this; it should never happen.",
                        Toast.LENGTH_LONG).show();
            } catch (NoSuchAlgorithmException e) {
                Log.e("MyApp", e.toString());
                Toast.makeText(this,
                        "MD5 Algorithm not found! Please report this.",
                        Toast.LENGTH_LONG).show();
            }
        }

        editor = sharedPref.edit();
    }

    private static final int FILE_SELECT_CODE = 0;

    public void showFileChooser(View v) {
        Intent intent = new Intent(Intent.ACTION_GET_CONTENT);
        intent.setType("*/*");
        intent.addCategory(Intent.CATEGORY_OPENABLE);

        try {
            startActivityForResult(
                    Intent.createChooser(intent, "Select Certificate File"), FILE_SELECT_CODE);
        } catch (android.content.ActivityNotFoundException ex) {
            Toast.makeText(this, "Please install a File Manager.", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        switch (requestCode) {
            case FILE_SELECT_CODE:
                if (resultCode == RESULT_OK) {
                    Uri uri = data.getData(); // Get the Uri of the selected file
                    Log.d("MyApp", "File Uri: " + uri.toString());

                    String path = uri.getPath(); // Get the path
                    Log.d("MyApp", "File Path: " + path);

                    // Make sure this file is a certificate
                    FileInputStream fis;
                    try { fis = new FileInputStream(path); }
                    catch (FileNotFoundException e) {
                        Log.d("MyApp", e.toString());
                        Toast.makeText(
                                this, "Could not read the selected file!", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Do not try to copy and store all the bytes from the file without first
                    // knowing it is a X509 certificate file, which is detected in loadCertificate()
                    // by the generateCertificate() call.
                    try {
                        loadCertificate(new BufferedInputStream(fis));
                    } catch(CertificateException e) {
                        Log.d("MyApp", e.toString());
                        Toast.makeText(this,
                                "This file is not a X509 certificate!", Toast.LENGTH_LONG).show();
                        return;
                    }

                    // Accept this certificate (even if not yet/no longer valid) by storing it in
                    // encoded form in SharedPreferences
                    try {
                        byte[] fileData = new byte[(int)fis.getChannel().size()];
                        fis.getChannel().position(0); // Go back to the beginning of the file

                        // Now it's safer to read the whole thing
                        DataInputStream dis = new DataInputStream(new BufferedInputStream(fis));
                        dis.readFully(fileData);
                        dis.close();

                        // Certificate files should be small, so there shouldn't be much issue with
                        // storing them persistently in SharedPreferences instead of a file
                        String encoded = Base64.encodeToString(fileData, Base64.NO_WRAP);

                        editor.putString("CERT", encoded);
                        editor.apply();

                        md5Fingerprint.setText(generateMD5Fingerprint(fileData));
                        md5Fingerprint.setTypeface(null, Typeface.NORMAL);
                        // TODO: User can import cert and then press return h/w button. If cert is
                        // valid, buttons should be clickable, but they aren't.
                    } catch (IOException e) {
                        Log.e("MyApp", e.toString());
                        Toast.makeText(
                                this, "Problem reading certificate file!",
                                Toast.LENGTH_LONG).show();
                    } catch (NoSuchAlgorithmException e) {
                        Log.e("MyApp", e.toString());
                        Toast.makeText(
                                this, "MD5 Algorithm not found! Please report this.",
                                Toast.LENGTH_LONG).show();
                    }
                }
                break;
        }
        super.onActivityResult(requestCode, resultCode, data);
    }

    private X509Certificate loadCertificate(InputStream is) throws CertificateException {
        X509Certificate cert = (X509Certificate) CertificateFactory.getInstance("X.509").
                                generateCertificate(is);

        // Make sure the current date is within the validity range of the certificate
        try {
            cert.checkValidity();
            certInfo.setText("");
        }
        catch (CertificateExpiredException e) {
            DateFormat df = DateFormat.getDateInstance();
            certInfo.setText(String.format(getString(R.string.cert_expired),
                    df.format(cert.getNotAfter())));
        } catch (CertificateNotYetValidException e) {
            DateFormat df = DateFormat.getDateInstance();
            certInfo.setText(String.format(getString(R.string.cert_not_yet_valid),
                    df.format(cert.getNotBefore())));
        }

        return cert;
    }

    private String generateMD5Fingerprint(byte[] data) throws NoSuchAlgorithmException {
        MessageDigest mdEnc = MessageDigest.getInstance("MD5"); // throws

        final String md5 = new BigInteger(1, mdEnc.digest(data)).toString(16);
        StringBuilder sb = new StringBuilder();
        for(int i=0;i<md5.length();i+=2) {
            sb.append(md5, i, i+2);
            if(i+2 < md5.length()) sb.append(":");
        }

        return sb.toString();
    }

    private boolean validIPAddress(String s) {
        if(!IP_ADDRESS.matcher(s).matches()) {
            Toast.makeText(
                    SettingsActivity.this, R.string.invalid_ip_address, Toast.LENGTH_LONG).show();
            return false;
        } else { // Save the valid IP address
            editor.putString("IP", s);
            editor.apply();
            return true;
        }
    }

    private boolean validPortNumber(String s) {
        Integer port;
        try {
            port = Integer.parseInt(s);
        } catch(NumberFormatException e) {
            Log.d("MyApp", e.toString());
            Toast.makeText(
                    SettingsActivity.this, R.string.invalid_port_number, Toast.LENGTH_LONG).show();
            return false;
        }

        if(port < 1 || port > 65535) {
            Toast.makeText(
                    SettingsActivity.this, R.string.invalid_port_number, Toast.LENGTH_LONG).show();
            return false;
        } else { // Save the valid port number
            editor.putString("PORT", s);
            editor.apply();
            return true;
        }
    }

    private boolean certificateImported() {
        return !md5Fingerprint.getText().toString().equals(getString(R.string.empty_fingerprint));
    }

    public void saveSettings(View v) {
        boolean validData =
                     validIPAddress(ipAddress.getText().toString());
        validData &= validPortNumber(portNumber.getText().toString());
        validData &= certificateImported();

        if(validData) {
            editor.putBoolean("SETUP", true);
            editor.apply();
            finish();
        } //TODO: Warn with Toast if not valid data
    }

}
