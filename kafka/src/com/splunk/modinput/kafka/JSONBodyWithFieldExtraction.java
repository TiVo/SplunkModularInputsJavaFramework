package com.splunk.modinput.kafka;

import java.lang.IllegalStateException;
import java.lang.Number;
import java.lang.NumberFormatException;
import java.nio.charset.Charset;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Map;

import org.apache.log4j.Logger;

import org.json.JSONObject;

import com.splunk.modinput.kafka.KafkaModularInput.MessageReceiver;

public class JSONBodyWithFieldExtraction extends AbstractMessageHandler {

    protected static Logger logger = Logger.getLogger(JSONBodyWithFieldExtraction.class);

    String charset = Charset.defaultCharset().name();
    String timefield = "";
    String hostfield = "";
    DateTimeFormatter timeformatPattern = null;
    int timeformatDivisor = 1;

    @Override
    public void handleMessage(String topic, byte[] messageContents, MessageReceiver context)
            throws Exception {

        String text = getMessageBody(messageContents, charset);

        JSONObject json = new JSONObject(text);

        Instant time = null;
        if (timefield.length() > 0) {
            String timeStr = json.getString(timefield);
            if (timeformatPattern != null) {
                try {
                    time = OffsetDateTime.parse(timeStr, timeformatPattern).toInstant();
                } catch (DateTimeParseException e) {
                    logger.warn("Unable to parse time format: " + timeStr);
                    // fall back to arrival time
                }
            } else {
                try {
                    long timeNum = Long.parseLong(timeStr);
                    switch (timeformatDivisor) {
                        case 1:
                            time = Instant.ofEpochSecond(timeNum);
                            break;
                        case 1000:
                            time = Instant.ofEpochMilli(timeNum);
                            break;
                        default:
                            throw new IllegalStateException("Unsupported time format divisor: " + timeformatDivisor);
                    }
                } catch (NumberFormatException e) {
                    logger.warn("Unable to parse time number: " + timeStr);
                    // fall back to arrival time
                }
            }
        }

        String host = "";
        if (hostfield.length() > 0) {
            host = json.getString(hostfield);
        }

        transportMessage(
                text,
                (time != null)
                        ? time.getEpochSecond() + "." + String.format("%09d", time.getNano())
                        : "",
                host,
                context.getStanzaName() + "/" + topic);
    }

    @Override
    public void setParams(Map<String, String> params) {

        if (params.containsKey("charset"))
            this.charset = params.get("charset");
        if (params.containsKey("timefield"))
            this.timefield = params.get("timefield");
        if (params.containsKey("timeformat")) {
            String pattern = params.get("timeformat");
            if (pattern.equals("{seconds}")) {
                this.timeformatDivisor = 1;
            } else if (pattern.equals("{milliseconds}")) {
                this.timeformatDivisor = 1000;
            } else {
                this.timeformatPattern = DateTimeFormatter.ofPattern(pattern);
            }
        }
        if (params.containsKey("hostfield")) {
            this.hostfield = params.get("hostfield");
        }
    }
}
