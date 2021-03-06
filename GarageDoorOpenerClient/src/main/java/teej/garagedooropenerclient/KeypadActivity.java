package teej.garagedooropenerclient;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.support.v4.app.ActivityCompat;
import android.support.v4.content.ContextCompat;
import android.util.Base64;
import android.util.Log;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.Button;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.lang.reflect.Field;
import java.net.ConnectException;
import java.net.InetAddress;
import java.net.Socket;
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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.SSLSocketFactory;
import javax.net.SocketFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

// TODO:
//  Prepare for release: http://developer.android.com/tools/publishing/preparing.html
//  Dump log(cat) to file for reporting (new permission required)
//  Get authorization result from server and notify user?
//  Ensure certificate falls within validity range for every submittal, not just when imported?
//  Socket connection timeout (Currently, effectively 2s because of 2s offer timeout for SUBMIT)

public class KeypadActivity extends Activity implements
        View.OnClickListener,                // For buttons 0-9
        PopupMenu.OnMenuItemClickListener {  // For Settings/About menu

    // Widgets
    private Button[] buttons      = new Button[10]; // For each of the digits
    private Button   clearButton  = null; // The button which clears all text from the code TextView
    private Button   submitButton = null; // The button which submits the code to the server
    private TextView code         = null; // The TextView which will contain the user-entered code

    // Stored settings like the server's public key, IP address, and port number
    private SharedPreferences sharedPref = null;

    // Type for the state of the socket used to connect to the server
    private enum State { CONNECTING, CONNECTED, DISCONNECTING, DISCONNECTED }

    // Events that can be passed to the AsyncTask managing the socket
    public enum EventType { MAINTAIN, SUBMIT }
    private class Event {
        Event(EventType type)              { this.type = type; }
        Event(EventType type, String code) { this.type = type; this.code = code; }
        public EventType type;
        public String    code;
    }

    // The event queue shared by the UI thread and the AsyncTask managing the socket
    // Note that it is a SynchronousQueue
    private BlockingQueue<Event> eventQueue = new SynchronousQueue<>();

    // Reference to the most recently executed task managing the socket connected to the server
    AsyncTask<Void, Void, Void> cmt_Task = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_my);

        // Store reference to the TextView which will contain the user-entered code
        code = (TextView) findViewById(R.id.code);
        if( code == null ) {
            Log.wtf("GDO", "Could not find code TextView");
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
                Log.wtf("GDO", "Button not found by id! (button" + i + ")");
                System.exit(1);
            }
        }

        // Store reference to the submit button
        submitButton = (Button) findViewById(R.id.submit_button);
        if(submitButton == null) {
            Log.wtf("GDO", "Could not find submit button View!");
            System.exit(1);
        }

        // Store reference to the clear button
        clearButton = (Button) findViewById(R.id.clear_button);
        if(clearButton == null) {
            Log.wtf("GDO", "Could not find clear button View!");
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

        requestAppPermissions();
    }

    private static int REQUEST_READ_STORAGE_REQUEST_CODE = 42;

    private void requestAppPermissions() {
        if (android.os.Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
            return;
        }

        if (hasReadPermissions()) {
            return;
        }

        ActivityCompat.requestPermissions(this,
                new String[] {
                        Manifest.permission.READ_EXTERNAL_STORAGE,
                }, REQUEST_READ_STORAGE_REQUEST_CODE);
    }

    private boolean hasReadPermissions() {
        return (ContextCompat.checkSelfPermission(
                getBaseContext(),
                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED);
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

            if(cmt_Task == null ||
               ((connectAndMaintainTask)cmt_Task).getState() == State.DISCONNECTED ||
               ((connectAndMaintainTask)cmt_Task).getState() == State.DISCONNECTING) {
                cmt_Task = new connectAndMaintainTask().execute();
            }

            try {
                eventQueue.offer(new Event(EventType.MAINTAIN), 50, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {}
        }
        else {
            Log.wtf("GDO", "onClick called with non-Button View");
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
            Log.d("GDO", "Settings button pressed");
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

        if(cmt_Task == null ||
           ((connectAndMaintainTask)cmt_Task).getState() == State.DISCONNECTED ||
           ((connectAndMaintainTask)cmt_Task).getState() == State.DISCONNECTING) {
            cmt_Task = new connectAndMaintainTask().execute();
        }

        try {
            if(!eventQueue.offer( // Blocks, deliberately!
                new Event(EventType.SUBMIT, code.getText().toString()), 2, TimeUnit.SECONDS)) {
                Toast.makeText(getApplication().getBaseContext(),
                        "Failed to connect to server!", Toast.LENGTH_SHORT).show();
            }
        } catch (InterruptedException e) {}

        clearCode(null);
    }

    // Clear the contents of the code TextView when the user presses the Clear button
    public void clearCode(View view) { code.setText(""); }


    private class connectAndMaintainTask extends AsyncTask<Void, Void, Void> {

        private Exception exception = null;

        private SSLSocket c;
        private BufferedWriter w;
//        private BufferedReader r; Currently Unused

        // The current state of this task, and a synchronization lock for it
        private State state = State.CONNECTING;
        private final Object stateLock = new Object();

        public State getState() {
            synchronized (stateLock) {
                return state;
            }
        }


        @Override
        protected Void doInBackground(Void... params) {
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
                            Log.wtf("GDO", "CERT shared preference is empty!");
                            throw new CertificateException("CERT shared preference empty");
                        }

                        // Get public key
                        PublicKey publicKey = server_cert.getPublicKey();

                        for (X509Certificate cert : chain) {
                            try {
                                cert.verify(publicKey); // Verifying by public key
                            } catch (NoSuchAlgorithmException | InvalidKeyException |
                                    NoSuchProviderException  | SignatureException e) {
                                Log.w("GDO", e.toString());
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
                sslContext.init(null, new TrustManager[]{tm}, null);

                Log.d("GDO", "Creating socket factory...");
                // http://stackoverflow.com/questions/4737019/tls-connection-using-sslsocket-is-slow-in-android-os
                final SSLSocketFactory delegate = sslContext.getSocketFactory();
                SocketFactory factory = new SSLSocketFactory() {
                    @Override
                    public Socket createSocket(String host, int port)
                            throws IOException, UnknownHostException {
                        InetAddress addr = InetAddress.getByName(host);
                        injectHostname(addr, host);
                        return delegate.createSocket(addr, port);
                    }
                    @Override
                    public Socket createSocket(InetAddress host, int port)
                            throws IOException {
                        return delegate.createSocket(host, port);
                    }
                    @Override
                    public Socket createSocket(String host, int port, InetAddress localHost, int localPort)
                            throws IOException, UnknownHostException {
                        return delegate.createSocket(host, port, localHost, localPort);
                    }
                    @Override
                    public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                            throws IOException {
                        return delegate.createSocket(address, port, localAddress, localPort);
                    }
                    private void injectHostname(InetAddress address, String host) {
                        try {
                            Field field = InetAddress.class.getDeclaredField("hostName");
                            field.setAccessible(true);
                            field.set(address, host);
                        } catch (Exception ignored) {
                        }
                    }
                    @Override
                    public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException {
                        injectHostname(s.getInetAddress(), host);
                        return delegate.createSocket(s, host, port, autoClose);
                    }
                    @Override
                    public String[] getDefaultCipherSuites() {
                        return delegate.getDefaultCipherSuites();
                    }
                    @Override
                    public String[] getSupportedCipherSuites() {
                        return delegate.getSupportedCipherSuites();
                    }
                };

                Log.d("GDO", "Creating socket...");
                c = (SSLSocket) factory.createSocket(
                        sharedPref.getString("IP", ""),
                        Integer.parseInt(sharedPref.getString("PORT", "")));

                c.setEnabledProtocols(new String[]{"TLSv1.2"});

                Log.d("GDO", "Starting handshake...");
                c.startHandshake();

                w = new BufferedWriter(new OutputStreamWriter(c.getOutputStream()));
//                r = new BufferedReader(new InputStreamReader(c.getInputStream())); Currently Unused

                Log.d("GDO", "Connected!");

                synchronized (stateLock) { state = State.CONNECTED; }

                while(true) {
                    Event e = eventQueue.poll(15, TimeUnit.SECONDS);
                    // Small window here where state is about to be wrong for submitCode, and any
                    // SUBMIT events it adds will not be handled (within the 2s timeout) because no
                    // new async task will be created. Low likelihood because of time required to
                    // perform all keypresses for a code (compared to handler code below).
                    synchronized (stateLock) {
                        if (e == null) {
                            state = State.DISCONNECTING;
                            Log.d("GDO", "Timeout waiting for MAINTAIN");
                            break;
                        } else if (e.type == EventType.MAINTAIN) {
                            // Allow another 15s before closing
                            Log.d("GDO", "MAINTAIN received");
                            continue;
                        } else if (e.type == EventType.SUBMIT) {
                            state = State.DISCONNECTING;

                            Log.d("GDO", "Writing the code on the socket...");
                            w.write(e.code.length());
                            w.write(e.code, 0, e.code.length());
                            w.newLine();

                            Log.d("GDO", "Flushing socket buffer...");
                            w.flush();
                            break;
                        } else {
                            state = State.DISCONNECTING;
                            Log.wtf("GDO", "Unrecognized EventType!");
                            break;
                        }
                    }
                }

            } catch (Exception e) {
                // Handle exceptions in onPostExecute()
                this.exception = e;
            } finally {
                synchronized (stateLock) {
                    state = State.DISCONNECTED;

                    Log.d("GDO", "Closing socket...");
                    try { if(c != null) c.close(); }
                    catch (IOException ex) {
                        Log.e("GDO", "Error closing socket: " + ex.toString());
                    }
                }
            }

            return null;
        }

        @Override
        protected void onPostExecute(Void result) {
            if(exception != null) {
                Log.d("GDO", exception.toString());

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
        }
    }

}


