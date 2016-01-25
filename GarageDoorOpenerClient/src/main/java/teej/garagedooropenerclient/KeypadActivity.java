package teej.garagedooropenerclient;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.AsyncTask;
import android.os.Bundle;
import android.util.Base64;
import android.util.Log;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.InvalidKeyException;
import java.security.KeyManagementException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PublicKey;
import java.security.SignatureException;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.Random;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// TODO:
//  Prepare for release: http://developer.android.com/tools/publishing/preparing.html
//  Dump log(cat) to file for reporting (new permission required)
//  Create the socket when a user starts entering a code, and close it after non-submission timeout
//    in order to reduce latency
//  Get authorization result from server and notify user?
//  Ensure certificate falls within validity range for every submittal, not just when imported?

public class KeypadActivity extends Activity implements
        View.OnClickListener,                // For buttons 0-9
        PopupMenu.OnMenuItemClickListener {  // For Settings/About menu

    // Widgets
    private Button[] buttons      = new Button[10]; // For each of the digits
    private Button   clearButton  = null; // The button which clears all text from the code TextView
    private Button   submitButton = null; // The button which submits the code to the server
    private TextView code         = null; // The TextView which will contain the user-entered code

    private SharedPreferences sharedPref = null;

    AsyncTask<String, Void, Void> submitCode_Task = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        // Store reference to the TextView which will contain the user-entered code
        code = (TextView) findViewById(R.id.code);
        if( code == null ) {
            Log.wtf("MyApp", "Could not find code TextView");
            System.exit(1);
        }

        // Store references to button objects which will contain digits 0-9
        for(int i=0;i<buttons.length;++i) {
            int id = getResources().getIdentifier("button" + i, "id", getPackageName());
            if( id != 0 ) {
                buttons[i] = (Button) findViewById(id);
                buttons[i].setText(Integer.toString(i));
                buttons[i].setOnClickListener(this);
            }
            else {
                Log.wtf("MyApp", "Button not found by id! (button" + i + ")");
                System.exit(1);
            }
        }

        // Store reference to the submit button
        submitButton = (Button) findViewById(R.id.submit_button);
        if(submitButton == null) {
            Log.wtf("MyApp", "Could not find submit button View!");
            System.exit(1);
        }

        // Store reference to the clear button
        clearButton = (Button) findViewById(R.id.clear_button);
        if(clearButton == null) {
            Log.wtf("MyApp", "Could not find clear button View!");
            System.exit(1);
        }

        // Randomize the initial order of the numbers
        shuffleButtons();

        // Warn and disable buttons if user needs to provide settings
        sharedPref = getApplicationContext().getSharedPreferences(
                getString(R.string.preferences_file_key), Context.MODE_PRIVATE);
        if(!sharedPref.getBoolean("SETUP", false)) {
            new AlertDialog.Builder(KeypadActivity.this)
                .setTitle("Configuration Required")
                .setMessage("Settings must be configured before this app is usable.")
                .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int which) {} })
                .setIcon(android.R.drawable.ic_dialog_info)
                .show();

            for(Button button : buttons) {
                button.setEnabled(false);
            }
            clearButton.setEnabled(false);
            submitButton.setEnabled(false);
        }
    }

    // Called upon return from the settings or about activities
    @Override
    protected void onResume() {
        if(sharedPref.getBoolean("SETUP", false)) {
            for(Button button : buttons) {
                button.setEnabled(true);
            }
            clearButton.setEnabled(true);
            submitButton.setEnabled(true);
        }
        super.onResume();
    }

    // Randomly shuffle the display order of the buttons
    private void shuffleButtons() {
        Random rnd = new Random();
        for( int i = buttons.length - 1; i > 0; --i ) {
            int idx = rnd.nextInt(i + 1);

            CharSequence tmp = buttons[idx].getText();
            buttons[idx].setText(buttons[i].getText());
            buttons[i].setText(tmp);
        }
    }

    // Append the clicked button's value to the code TextView
    public void onClick(View v) {
        if(v instanceof Button) {
            Button b = (Button)v;
            code.append(b.getText());// TODO: Only if TextView is not already full (in which case, we should have already set the buttons to not be clickable)
            // TODO: Only enable submit button if there is a code in the TextView to submit
        }
        else {
            Log.wtf("MyApp", "onClick called with non-Button View");
        }
    }

    // Display the menu when the icon is clicked by the user
    public void showPopupMenu(View v) {
        PopupMenu popup = new PopupMenu(this, v);
        popup.setOnMenuItemClickListener(this);
        MenuInflater inflater = popup.getMenuInflater();
        inflater.inflate(R.menu.menu_my, popup.getMenu());
        popup.show();
    }

    @Override
    public boolean onMenuItemClick(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.action_settings) {
            Log.d("MyApp", "Settings button pressed");
            Intent intent = new Intent(this, SettingsActivity.class);
            // TODO Use startActivityForResult
            startActivity(intent);
        } else if(id == R.id.action_about) {

            // TODO: Make this much prettier by using a new activity instead of this dialog
            new AlertDialog.Builder(KeypadActivity.this)
                    .setTitle("About")
                    .setMessage("Android client for the GarageDoorOpener project.\n" +
                            "See https://github.com/tweej/GarageDoorOpener\nand\n" +
                            "https://github.com/tweej/GarageDoorOpener-Android-Client.\n"+
                            "Author: Thomas Mercier 2015")
                    .setNeutralButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                        public void onClick(DialogInterface dialog, int which) {} })
                    .setIcon(android.R.drawable.ic_dialog_info)
                    .show();
        }

        return true;
    }

    // Submit the code displayed in the code TextView to the server and shuffle the buttons
    public void submitCode(View view) {
        shuffleButtons();

        // We only want a single code submission task at any one time
        if(submitCode_Task != null) {
            Log.d("MyApp", "Cancelling current code submission task...");
            submitCode_Task.cancel(true);
        }
        Log.d("MyApp", "Connecting...");
        submitCode_Task = new SubmitCodeTask().execute(code.getText().toString());
        clearCode(null);
    }

    // Clear the contents of the code TextView when the user presses the Clear button
    public void clearCode(View view) { code.setText(""); }

    private class SubmitCodeTask extends AsyncTask<String, Void, Void> {

        private Exception exception = null;

        protected Void doInBackground(String... code) {
            try {
                TrustManager tm = new X509TrustManager() {
                    @Override
                    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {}

                    @Override
                    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
                        X509Certificate server_cert;
                        String EncodedCert = sharedPref.getString("CERT", getString(R.string.empty_fingerprint));

                        if(!EncodedCert.equals(getString(R.string.empty_fingerprint))) {
                            byte[] decoded = Base64.decode(EncodedCert, Base64.NO_WRAP);
                            InputStream is = new ByteArrayInputStream(decoded);

                            // Do not verify cert has not expired, or is not valid.
                            // Just warn in the settings page.
                            server_cert = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(is);
                        } else {
                            Log.wtf("MyApp", "CERT shared preference is empty!");
                            throw new CertificateException("CERT shared preference empty");
                        }

                        // Get public key
                        PublicKey publicKey = server_cert.getPublicKey();

                        for (X509Certificate cert : chain) {
                            try {
                                cert.verify(publicKey); // Verifying by public key
                            } catch (NoSuchAlgorithmException | InvalidKeyException |
                                     NoSuchProviderException  | SignatureException e) {
                                Log.w("MyApp", e.toString());
                                throw new CertificateException("Could not verify server is who it claims to be!");
                            }
                        }
                    }

                    @Override
                    public X509Certificate[] getAcceptedIssuers() {
                        return null;
                    }
                };

                // TLSv1.2 supported in API 16+; enabled by default in API 20+
                SSLContext sslContext = SSLContext.getInstance("TLSv1.2");
                sslContext.init(null, new TrustManager[] { tm }, null);

                Log.d("MyApp", "Creating socket...");
                SSLSocket c = (SSLSocket) sslContext.getSocketFactory().createSocket(
                        InetAddress.getByName(sharedPref.getString("IP", "")).getHostAddress(),
                        Integer.parseInt(sharedPref.getString("PORT", "")));

                c.setEnabledProtocols(new String[] {"TLSv1.2"});

                Log.d("MyApp", "Starting handshake...");
                c.startHandshake();

                BufferedWriter w = new BufferedWriter(new OutputStreamWriter(c.getOutputStream()));
                BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream()));

                Log.d("MyApp", "Writing the code on the socket...");
                w.write(code[0].length());
                w.write(code[0], 0, code[0].length());
                w.newLine();

                Log.d("MyApp", "Flushing socket buffer...");
                w.flush();

                Log.d("MyApp", "Closing socket...");
                w.close(); r.close(); c.close();

            } catch (Exception e) {
                // Handle exceptions in onPostExecute()
                this.exception = e;
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if(exception != null) {
                Log.d("MyApp", exception.toString());

                Context ctx = getApplication().getBaseContext();

                // Failed to connect to server after Android's default timeout
                if(exception instanceof ConnectException) {
                    Toast.makeText(ctx, "Failed to connect to server.", Toast.LENGTH_SHORT).show();
                } // Verify public key provided by server is trusted
                else if(exception instanceof CertificateException ||
                        exception instanceof SSLHandshakeException) { //http://stackoverflow.com/a/8053459/1800880
                    Toast.makeText(ctx,
                            "Could not verify server is who it claims to be!", Toast.LENGTH_SHORT).show();
                } // Get the SSLContext instance
                else if(exception instanceof NoSuchAlgorithmException ||
                        exception instanceof NullPointerException) {
                    Toast.makeText(ctx,
                            R.string.protocol_not_supported, Toast.LENGTH_SHORT).show();
                } // Initialize SSLContext. KeyManager is hardcoded to null, so should never happen
                else if(exception instanceof KeyManagementException) {
                    Toast.makeText(ctx,
                            "Initializing SSLContext failed! This should never happen.", Toast.LENGTH_LONG).show();
                } // Call getByName() on IP/Hostname
                else if(exception instanceof UnknownHostException) {
                    Toast.makeText(ctx, "Hostname/IP lookup failed!", Toast.LENGTH_SHORT).show();
                } // call createSocket()
                else if(exception instanceof IOException) {
                    Toast.makeText(ctx,
                            "Unable to either create or use socket!", Toast.LENGTH_SHORT).show();
                } // Set the enabled protocols
                else if(exception instanceof IllegalArgumentException) {
                    Toast.makeText(ctx, R.string.protocol_not_supported, Toast.LENGTH_SHORT).show();
                } else {
                    Toast.makeText(ctx,
                            "Unexpected exception! Please report this!", Toast.LENGTH_LONG).show();
                }
            }

            // No need to attempt to cancel a completed AsyncTask on next submit
            submitCode_Task = null;
        }
    }

}


