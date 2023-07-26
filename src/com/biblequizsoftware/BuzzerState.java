package com.biblequizsoftware;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;

import javax.swing.*;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.filechooser.FileSystemView;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.ItemEvent;
import java.awt.event.ItemListener;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashMap;

public class BuzzerState {
    @FunctionalInterface
    public interface BuzzerCallback {
        void action(BUZZER_CMD cmd, int timestamp);
    }

    public enum BUZZER_CMD {
        R1,
        R2,
        R3,
        Y1,
        Y2,
        Y3,
        QM,
        CLEAR
    }

    private final short BT_PACKET_CMD = 0;
    private final short BT_PACKET_LOG = 1;
    private final short BT_PACKET_PING = 2;
    private final short BT_PACKET_BQT_PING = 3;

    private final short BT_PACKET_OTA_START = 10;
    private final short BT_PACKET_OTA_MSG = 11;
    private final short BT_PACKET_OTA_END = 12;
    private final short BT_PACKET_OTA_REQ = 13;

    private final short BT_PACKET_SPIFFS_START = 20;
    private final short BT_PACKET_SPIFFS_MSG = 21;
    private final short BT_PACKET_SPIFFS_END = 22;

    private final short BT_PACKET_CONFIG_WRITE = 30;
    private final short BT_PACKET_CONFIG_READ = 31;

    private final short BT_TIMESTAMP = (short) (1 << 15);

    private final int PACKET_SIZE = 100;
    private final int INITIAL_BURST_SIZE = 1;
    private final int PACKET_SLEEP = 1;

    private LocalDateTime lastOTAReqTime = null;

    private JComboBox<SerialPort> cboDevices;
    private JButton scanButton;
    private JTextField textState;
    private JButton connectButton;
    private JTextField textFirmware;
    public JPanel rootPanel;
    private JCheckBox chkRGBTimerEnabled;
    private JComboBox<String> cboVolume;
    private JCheckBox chkQMTimerEnabled;
    private JProgressBar progressBar1;
    private JButton updateButton;

    private boolean connected = false;

    private final BuzzerCallback callback;

    private SerialPort[] matchingPorts;

    private SerialPort readPort;

    private LocalDateTime lastPingTime = null;

    private final HashMap<String, String> readConfig;
    private boolean updatingConfig = false;

    int state = 0; // 0 = start of packet, 1 = middle of packet
    byte[] remainingData;

    byte[] firmwareData;

    private SerialPort[] scannedPorts;

    private final String[] volumeLabels = new String[]{
        "Volume 0%",
        "Volume 10%",
        "Volume 20%",
        "Volume 30%",
        "Volume 40%",
        "Volume 50%",
        "Volume 60%",
        "Volume 70%",
        "Volume 80%",
        "Volume 90%",
        "Volume 100%",
    };

    private final String[] configItems = new String[] {
        "version",
        "volume",
        "rgb_timer",
        "qm_timer",
    };

    private Thread otaRetry = null;

    private void scanForDevices() {
        scannedPorts = SerialPort.getCommPorts();

        // return all ports that start with (probably equal) 'BQS_BUZZER' (v.19) or 'Buzzer'
        matchingPorts = Arrays.stream(scannedPorts).filter(p -> p.getPortDescription().startsWith("BQS_BUZZER") || p.getPortDescription().startsWith("Buzzer")).toArray(SerialPort[]::new);
        cboDevices.removeAllItems();
        Arrays.stream(matchingPorts).forEach(port -> {
            cboDevices.addItem(port);
        });
    }

    private short checksum(byte[] data, int start, int len)
    {
        int sum = 0;
        for (int i = start; i < start + len; i++)
            sum += data[i];
        return (short)sum;
    }

    private void write(final short type) {
        // get ascii bytes
        writePacket(type, new byte[0]);
    }

    private void write(final short type, String packet) {
        // get ascii bytes
        writePacket(type, packet.getBytes());
    }

    private void write(final short type, int value) {
        byte[] payload = new byte[4];
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        bb.putInt(0, value);
        writePacket(type, payload);
    }

    private void writePacket(final short type, byte[] packetData) {
        if (readPort == null)
            return;

        byte[] payload = new byte[packetData.length + 4];
        ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
        bb.putShort(0, type);
        bb.putShort(2, (short)packetData.length);
        if (packetData.length > 0) {
            bb.put(4, packetData);
        }
        readPort.writeBytes(payload, payload.length);
    }

    private void sendPacket(byte[] data, int packetIndex, int packetSize)
    {
        int offset = packetIndex * packetSize;

        if (offset < data.length)
        {
            int len = Math.min(packetSize, data.length - offset);
            byte[] packetData = Arrays.copyOfRange(data, offset, offset + len);

            byte[] payload = new byte[len + 4];
            ByteBuffer bb = ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN);
            bb.putShort(0, (short) packetIndex);
            bb.put(2, packetData);
            bb.putShort(packetData.length + 2, checksum(packetData));

            writePacket(BT_PACKET_OTA_MSG, payload);
        }
    }

    private short checksum(byte[] data) {
        int csum = 0;
        for (byte b : data) {
            csum += (int)b & 0xff;
        }
        return (short)csum;
    }

    private boolean requestNextConfigItem() {
        for (String s : configItems) {
            if (!readConfig.containsKey(s)) {
                write(BT_PACKET_CONFIG_READ, s);
                return true;
            }
        }
        // no more to do!
        return false;
    }

    private void updateConfig() {
        updatingConfig = true;

        String key = "volume";
        if (readConfig.containsKey(key)) {
            try {
                final int value = Integer.parseInt(readConfig.get(key)) / 10;
                cboVolume.setSelectedItem(volumeLabels[value]);
            } catch (NumberFormatException ex) {
                // do nothing, just ignore
            }
        }

        key = "version";
        if (readConfig.containsKey(key)) {
            textFirmware.setText(readConfig.get(key));
        }

        key = "rgb_timer";
        if (readConfig.containsKey(key)) {
            try {
                final int value = Integer.parseInt(readConfig.get(key));
                chkRGBTimerEnabled.setSelected(value == 1);
            } catch (NumberFormatException ex) {
                // do nothing, just ignore
            }
        }

        key = "qm_timer";
        if (readConfig.containsKey(key)) {
            try {
                final int value = Integer.parseInt(readConfig.get(key));
                chkQMTimerEnabled.setSelected(value == 1);
            } catch (NumberFormatException ex) {
                // do nothing, just ignore
            }
        }

        updatingConfig = false;
    }

    private void processPacket(short type, byte[] packetData) {
        boolean timestamp = (type & BT_TIMESTAMP) != 0;
        final int eventTime;
        if (timestamp) {
            // read the last 4 bytes as a timestamp
            byte[] tsData = Arrays.copyOfRange(packetData, packetData.length - 4, packetData.length);
            eventTime = ByteBuffer.wrap(tsData).order(ByteOrder.LITTLE_ENDIAN).getInt(0);
            packetData = Arrays.copyOfRange(packetData, 0, packetData.length - 4);
        } else {
            eventTime = 0;
        }
        short baseType = (short) (type & ~BT_TIMESTAMP);
        switch (baseType) {
            case BT_PACKET_PING:
                lastPingTime = LocalDateTime.now();
                // send a response ping
                writePacket(BT_PACKET_BQT_PING, new byte[0]);
//                if (sendComplete && firmwareData != null) {
//                    write(BT_PACKET_OTA_REQ);
//                }
                break;
            case BT_PACKET_CONFIG_READ:
                try {
                    final String config = new String(packetData, StandardCharsets.US_ASCII);
                    int pos = config.indexOf("=");
                    String key = config.substring(0, pos);
                    String value = config.substring(pos + 1);
                    if (value.length() > 0) {
                        readConfig.put(key, value);
                        if (!requestNextConfigItem()) {
                            updateConfig();
                        }
                    }
                } catch (Exception ex) {
                }
                break;
            case BT_PACKET_CMD:
                try {
                    if (callback != null) {
                        final String cmd = new String(packetData, StandardCharsets.US_ASCII);
                        switch (cmd) {
                            case "R1":
                                callback.action(BUZZER_CMD.R1, eventTime);
                                break;
                            case "R2":
                                callback.action(BUZZER_CMD.R2, eventTime);
                                break;
                            case "R3":
                                callback.action(BUZZER_CMD.R3, eventTime);
                                break;
                            case "Y1":
                                callback.action(BUZZER_CMD.Y1, eventTime);
                                break;
                            case "Y2":
                                callback.action(BUZZER_CMD.Y2, eventTime);
                                break;
                            case "Y3":
                                callback.action(BUZZER_CMD.Y3, eventTime);
                                break;
                            case "CLEAR":
                                callback.action(BUZZER_CMD.CLEAR, eventTime);
                                break;
                            case "QM":
                                callback.action(BUZZER_CMD.QM, eventTime);
                                break;
                        }
                    }
                } catch (Exception ex) {
                }
                break;
            case BT_PACKET_OTA_REQ:
                {
                    final ByteBuffer bb = ByteBuffer.wrap(packetData).order(ByteOrder.LITTLE_ENDIAN);
                    int packet = bb.getShort(0);
                    System.out.println("Requested: " + packet);

                    if (packet * PACKET_SIZE > firmwareData.length)
                    {
                        // we've sent everything!!
                        write(BT_PACKET_OTA_END, firmwareData.length);
                        firmwareData = null;
                        lastOTAReqTime = null;
                        JOptionPane.showMessageDialog(rootPanel, "Firmware upload complete! You may need to forget/repair the device to confirm the update was successful.");
                    }
                    else
                    {
                        // read the packets requested and send
                        for (int i = 0; i < packetData.length / 2; i++)
                        {
                            packet = bb.getShort(i * 2);
                            System.out.println("Requested: " + packet);
                            sendPacket(firmwareData, packet, PACKET_SIZE);
                            progressBar1.setValue(packet);
                            try {
                                Thread.sleep(PACKET_SLEEP);
                            } catch (Exception ex) {
                            }
                        }

                        // send the request for next set of packets
                        write(BT_PACKET_OTA_REQ);
                        lastOTAReqTime = LocalDateTime.now();
                    }
                }
                break;
            default:
                break;
        }
    }

    private void processIncomingData(byte[] data) {
        if (state == 0) {
            // read the first two bytes as a short
            final short type = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(0);
            final int packetLen = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(2) + 4;
            if (packetLen <= data.length) {
                if (packetLen == 4) {
                    processPacket(type, new byte[0]);
                } else {
                    final byte[] packetData = Arrays.copyOfRange(data, 4, packetLen);
                    processPacket(type, packetData);
                }
                if (data.length > packetLen) {
                    // more data to process
                    processIncomingData(Arrays.copyOfRange(data, packetLen, data.length));
                }
            } else {
                // this wasn't complete, copy what we have and return
                remainingData = data;

                // change to state 1 - middle of packet
                state = 1;
            }
        } else {
            // combine the current data with the new data
            final byte[] allData = new byte[remainingData.length + data.length];
            System.arraycopy(remainingData, 0, allData, 0, remainingData.length);
            System.arraycopy(data, 0, allData, remainingData.length, data.length);

            // read packet len from the beginning of the packet
            final short type = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN).getShort(0);
            final int packetLen = ByteBuffer.wrap(allData).order(ByteOrder.LITTLE_ENDIAN).getShort(2) + 4;
            if (allData.length <= packetLen) {
                // it'll finish this cycle
                if (packetLen == 4) {
                    processPacket(type, new byte[0]);
                } else {
                    final byte[] packetData = Arrays.copyOfRange(allData, 4, packetLen - 4);
                    processPacket(type, packetData);
                }
                // return to state 0 - start of packet
                state = 0;
            } else {
                remainingData = allData;
                // remain in state 1
            }
        }
    }

    public BuzzerState(BuzzerCallback callback) {
        this.callback = callback;
        readConfig = new HashMap<>();

        for (String label : volumeLabels) {
            cboVolume.addItem(label);
        }
        cboVolume.setSelectedItem(volumeLabels[0]);

        scanForDevices();
        scanButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                scanForDevices();
            }
        });
        connectButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (!connected) {
                    if (cboDevices.getSelectedItem() != null) {
                        final SerialPort tmpReadPort = (SerialPort) cboDevices.getSelectedItem();

                        textState.setText("Connecting...");
                        rootPanel.revalidate();

                        if (tmpReadPort.openPort(10, 10, 10)) {
                            connectButton.setText("Disconnect");
                            textState.setText("Connected");


                            connected = true;
                            readPort = tmpReadPort;

                            // attach the listener
                            readPort.addDataListener(new SerialPortDataListener() {
                                @Override
                                public int getListeningEvents() { return SerialPort.LISTENING_EVENT_DATA_RECEIVED; }
                                @Override
                                public void serialEvent(SerialPortEvent event)
                                {
                                    byte[] newData = event.getReceivedData();
                                    processIncomingData(newData);
                                }
                            });

                            // request the config info
                            readConfig.clear();
                            if (!requestNextConfigItem()) {
                                updateConfig();
                            }
                        } else {
                            textState.setText("Connection failed!");
                        }
                    }
                } else {
                    readPort.closePort();
                    readPort = null;

                    connectButton.setText("Connect");
                    textState.setText("Disconnected");
                    connected = false;
                }
                updateButton.setEnabled(connected);
                chkRGBTimerEnabled.setEnabled(connected);
                chkQMTimerEnabled.setEnabled(connected);
                cboVolume.setEnabled(connected);
                textFirmware.setEnabled(connected);
            }
        });
        updateButton.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                JFileChooser j = new JFileChooser(FileSystemView.getFileSystemView().getHomeDirectory());

                // restrict the user to select files of all types
                j.setAcceptAllFileFilterUsed(false);

                // set a title for the dialog
                j.setDialogTitle("Select a firmware (*.bin) file");

                // only allow files of .txt extension
                FileNameExtensionFilter restrict = new FileNameExtensionFilter("Only .bin files", "bin");
                j.addChoosableFileFilter(restrict);

                // invoke the showsSaveDialog function to show the save dialog
                int r = j.showOpenDialog(null);

                if (r == JFileChooser.APPROVE_OPTION) {
                    // set the label to the path of the selected file
                    System.out.println(j.getSelectedFile().getAbsolutePath());

                    try {
                        firmwareData = Files.readAllBytes(j.getSelectedFile().toPath());
                        progressBar1.setMaximum(firmwareData.length / PACKET_SIZE);

                        write(BT_PACKET_OTA_START, firmwareData.length);

                        // give the device time to get ready for the packets
                        Thread.sleep(500);

                        // send the first burst of packets
                        for (int i = 0; i < INITIAL_BURST_SIZE; i++)
                        {
                            sendPacket(firmwareData, i, PACKET_SIZE);
                            progressBar1.setValue(i);
                        }

                        // send the request for next set of packets
                        write(BT_PACKET_OTA_REQ);
                        lastOTAReqTime = LocalDateTime.now();

                        if (otaRetry != null) {
                            otaRetry.interrupt();
                            otaRetry = null;
                        }
                        otaRetry = new Thread(()-> {
                            try {
                                while (lastOTAReqTime != null) {
                                    final long elapsedMs = ChronoUnit.MILLIS.between(lastOTAReqTime, LocalDateTime.now());
                                    if (elapsedMs > 5000) {
                                        write(BT_PACKET_OTA_REQ);
                                        lastOTAReqTime = LocalDateTime.now();
                                    }
                                    Thread.sleep(100);
                                }
                            } catch (InterruptedException ex) {
                                throw new RuntimeException(ex);
                            }
                        });
                        otaRetry.start();
                    } catch (IOException | InterruptedException ex) {
                        throw new RuntimeException(ex);
                    }
                }
            }
        });
        chkRGBTimerEnabled.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String cmd = "rgb_timer=" + (chkRGBTimerEnabled.isSelected() ? 1  :0) ;
                if (connected) {
                    write(BT_PACKET_CONFIG_WRITE, cmd);
                }
            }
        });
        chkQMTimerEnabled.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                String cmd = "qm_timer=" + (chkQMTimerEnabled.isSelected() ? 1  :0) ;
                if (connected) {
                    write(BT_PACKET_CONFIG_WRITE, cmd);
                }
            }
        });
        cboVolume.addItemListener(new ItemListener() {
            @Override
            public void itemStateChanged(ItemEvent e) {
                if (updatingConfig || e.getStateChange() == ItemEvent.DESELECTED) {
                    return;
                }
                String label = (String)e.getItem();
                for (int index = 0; index < volumeLabels.length; index++) {
                    if (label.compareTo(volumeLabels[index]) == 0) {
                        if (connected) {
                            try {
                                String cmd = "volume=" + (index * 10);
                                write(BT_PACKET_CONFIG_WRITE, cmd);
                                Thread.sleep(250);
                            } catch (InterruptedException ex) {
                            }
                        }
                        break;
                    }
                }
            }
        });
    }

    public void showDialog() {
        showDialog(false);
    }

    public void clearBuzzer() {
        write(BT_PACKET_CMD, "CLEAR");
    }

    public void showDialog(boolean exitOnClose) {
        JFrame frame = new JFrame("BQS Buzzer Control");
        frame.setContentPane(this.rootPanel);
        if (exitOnClose) {
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        } else {
            frame.setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        }
        frame.pack();
        frame.setVisible(true);
    }
}
