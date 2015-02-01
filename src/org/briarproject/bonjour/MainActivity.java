package org.briarproject.bonjour;

import static android.net.wifi.p2p.WifiP2pManager.NO_SERVICE_REQUESTS;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_PEERS_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION;
import static android.net.wifi.p2p.WifiP2pManager.WIFI_P2P_THIS_DEVICE_CHANGED_ACTION;
import static android.view.Gravity.CENTER;
import static android.view.Gravity.CENTER_HORIZONTAL;
import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.LinearLayout.VERTICAL;
import static android.widget.Toast.LENGTH_LONG;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.UUID;

import org.apache.http.conn.util.InetAddressUtils;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.wifi.WifiInfo;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pDeviceList;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ActionListener;
import android.net.wifi.p2p.WifiP2pManager.Channel;
import android.net.wifi.p2p.WifiP2pManager.DnsSdServiceResponseListener;
import android.net.wifi.p2p.WifiP2pManager.DnsSdTxtRecordListener;
import android.net.wifi.p2p.WifiP2pManager.PeerListListener;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pDnsSdServiceRequest;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.View.OnClickListener;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.LinearLayout.LayoutParams;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity implements OnClickListener {

	private static final String TAG = MainActivity.class.getPackage().getName();
	private static final String SERVICE_TYPE = "_example._tcp";
	private static final String SERVICE_NAME = "example";
	private static final UUID SERVICE_UUID = UUID.nameUUIDFromBytes(
			new byte[] {'e', 'x', 'a', 'm', 'p', 'l', 'e'});
	private static final int PORT = 45678;

	private ScrollView scroll;
	private TextView output;
	private ImageButton refresh;

	private WifiP2pManager p2p;
	private WifiManager wifi;
	private BluetoothAdapter bt;
	private Channel channel;
	private BroadcastReceiver receiver;
	private IntentFilter filter;
	private PeerListListener peerListListener;
	private DnsSdTxtRecordListener txtListener;
	private DnsSdServiceResponseListener serviceListener;
	private WifiP2pDnsSdServiceRequest serviceRequest;
	private WifiP2pDnsSdServiceInfo serviceInfo;

	private boolean btWasEnabled = false;

	private volatile ServerSocket wifiSocket = null;
	private volatile BluetoothServerSocket btSocket = null;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);

		LinearLayout layout = new LinearLayout(this);
		layout.setLayoutParams(new LayoutParams(MATCH_PARENT, MATCH_PARENT));
		layout.setOrientation(VERTICAL);
		layout.setGravity(CENTER_HORIZONTAL);
		scroll = new ScrollView(this);
		scroll.setLayoutParams(new LayoutParams(MATCH_PARENT, WRAP_CONTENT, 1));
		output = new TextView(this);
		output.setPadding(10, 10, 10, 10);
		scroll.addView(output);
		layout.addView(scroll);
		layout.addView(new HorizontalBorder(this));
		LinearLayout footer = new LinearLayout(this);
		footer.setLayoutParams(new LayoutParams(MATCH_PARENT, WRAP_CONTENT));
		footer.setGravity(CENTER);
		refresh = new ImageButton(this);
		refresh.setBackgroundResource(0);
		refresh.setImageResource(R.drawable.navigation_refresh);
		refresh.setOnClickListener(this);
		footer.addView(refresh);
		layout.addView(footer);
		setContentView(layout);

		p2p = (WifiP2pManager) getSystemService(WIFI_P2P_SERVICE);
		if(p2p == null) die("This device does not support Wi-Fi Direct");
		channel = p2p.initialize(this, getMainLooper(), null);

		wifi = (WifiManager) getSystemService(WIFI_SERVICE);
		if(wifi != null) openWifiSocket();

		bt = BluetoothAdapter.getDefaultAdapter();
		if(bt != null) {
			enableBluetooth();
			openBluetoothSocket();
		}

		receiver = new PeerReceiver();
		filter = new IntentFilter();
		filter.addAction(WIFI_P2P_STATE_CHANGED_ACTION);
		filter.addAction(WIFI_P2P_PEERS_CHANGED_ACTION);
		filter.addAction(WIFI_P2P_CONNECTION_CHANGED_ACTION);
		filter.addAction(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION);

		peerListListener = new PeerListListener() {

			public void onPeersAvailable(WifiP2pDeviceList peers) {
				print("Discovered peers:");
				for(WifiP2pDevice peer : peers.getDeviceList())
					print("\t" + deviceToString(peer));
			}
		};

		serviceListener = new DnsSdServiceResponseListener() {

			public void onDnsSdServiceAvailable(String instanceName,
					String serviceType, WifiP2pDevice device) {
				Log.d(TAG, "Discovered service:");
				Log.d(TAG, "\t" + instanceName + "\t" + serviceType);
				Log.d(TAG, "\t" + deviceToString(device));
			}
		};

		txtListener = new DnsSdTxtRecordListener() {

			public void onDnsSdTxtRecordAvailable(String domain,
					Map<String, String> txtMap, WifiP2pDevice device) {
				print("Discovered TXT record:");
				print("\t" + deviceToString(device));
				print("\t" + domain);
				for(Entry<String, String> e : txtMap.entrySet())
					print("\t" + e.getKey() + " = " + e.getValue());
				String ip = txtMap.get("ip");
				if(ip != null) connectByWifi(ip);
				String bt = txtMap.get("bt");
				if(bt != null) connectByBluetooth(bt);
			}
		};

		p2p.setDnsSdResponseListeners(channel, serviceListener, txtListener);

		serviceRequest = WifiP2pDnsSdServiceRequest.newInstance();

		String instanceName = "foo";
		Map<String, String> txtMap = new HashMap<String, String>();
		String ipAddress = getLocalIpAddress();
		if(ipAddress != null) txtMap.put("ip", ipAddress);
		String btAddress = getLocalBluetoothAddress();
		if(btAddress != null) txtMap.put("bt", btAddress);
		serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(instanceName,
				SERVICE_TYPE, txtMap);

		p2p.addLocalService(channel, serviceInfo, new ActionListener() {

			public void onSuccess() {}

			public void onFailure(int reason) {
				print("Adding local service failed, error code " + reason);
			}
		});
	}

	@Override
	public void onResume() {
		super.onResume();
		registerReceiver(receiver, filter);
		output.setText("");
		startDiscovery();
	}

	@Override
	public void onPause() {
		super.onPause();
		unregisterReceiver(receiver);
		stopDiscovery();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		if(wifi != null) closeWifiSocket();
		if(bt != null) {
			closeBluetoothSocket();
			disableBluetooth();
		}
	}

	public void onClick(View view) {
		if(view == refresh) {
			output.setText("");
			stopDiscovery();
			startDiscovery();
		}
	}

	private void die(String text) {
		Log.e(TAG, text);
		Toast.makeText(this, text, LENGTH_LONG).show();
		finish();
	}

	private void print(String text) {
		Log.d(TAG, text);
		output.append(text + "\n");
	}

	private String deviceToString(WifiP2pDevice device) {
		return device.deviceName + "\t" + device.deviceAddress;
	}

	private String ipIntToString(int ip) {
		int b0 = ip & 0xFF;
		int b1 = (ip >> 8) & 0xFF;
		int b2 = (ip >> 16) & 0xFF;
		int b3 = (ip >> 24) & 0xFF;
		return b0 + "." + b1 + "." + b2 + "." + b3;
	}

	private byte[] ipIntToBytes(int ip) {
		byte[] b = new byte[4];
		b[0] = (byte) (ip & 0xFF);
		b[1] = (byte) ((ip >> 8) & 0xFF);
		b[2] = (byte) ((ip >> 16) & 0xFF);
		b[3] = (byte) ((ip >> 24) & 0xFF);
		return b;
	}

	private String getLocalIpAddress() {
		if(wifi == null) return null;
		WifiInfo wifiInfo = wifi.getConnectionInfo();
		if(wifiInfo == null) return null;
		int ip = wifiInfo.getIpAddress();
		if(ip == 0) return null;
		return ipIntToString(ip);
	}

	private void openWifiSocket() {
		assert wifi != null;
		WifiInfo wifiInfo = wifi.getConnectionInfo();
		if(wifiInfo == null) return;
		final byte[] ip = ipIntToBytes(wifiInfo.getIpAddress());
		new Thread() {

			@Override
			public void run() {
				try {
					Log.d(TAG, "Opening wifi socket");
					InetAddress inet = InetAddress.getByAddress(ip);
					ServerSocket socket = new ServerSocket();
					socket.bind(new InetSocketAddress(inet, PORT));
					Log.d(TAG, "Wifi socket opened");
					wifiSocket = socket;
					while(true) {
						Socket s = socket.accept();
						final String addr = s.getInetAddress().getHostAddress();
						runOnUiThread(new Runnable() {
							public void run() {
								print("Incoming connection from " + addr);
							}
						});
						s.close();
					}
				} catch(IOException e) {
					Log.e(TAG, e.toString());
				}
			}
		}.start();
	}

	private void closeWifiSocket() {
		if(wifiSocket == null) return;
		Log.d(TAG, "Closing wifi socket");
		try {
			wifiSocket.close();
		} catch(IOException e) {
			Log.e(TAG, e.toString());
		}
	}

	private void connectByWifi(final String ip) {
		if(!isValidIpAddress(ip)) {
			Log.d(TAG, ip + " is not a valid IP address");
			return;
		}
		print("Connecting to " + ip);
		new Thread() {

			@Override
			public void run() {
				try {
					Socket s = new Socket(ip, PORT);
					runOnUiThread(new Runnable() {
						public void run() {
							print("Connected to " + ip);
						}
					});
					s.close();
				} catch(IOException e) {
					Log.e(TAG, e.toString());
				}
			}
		}.start();
	}

	private boolean isValidIpAddress(String ip) {
		if(!InetAddressUtils.isIPv4Address(ip)) return false;
		try {
			InetAddress inet = InetAddress.getByName(ip);
			return inet.isSiteLocalAddress();
		} catch(UnknownHostException e) {
			Log.e(TAG, e.toString());
			return false;
		}
	}

	private void enableBluetooth() {
		assert bt != null;
		if(bt.isEnabled()) btWasEnabled = true;
		else bt.enable();
	}

	private void disableBluetooth() {
		assert bt != null;
		if(!btWasEnabled) bt.disable();
	}

	private String getLocalBluetoothAddress() {
		if(bt == null) return null;
		String addr = bt.getAddress();
		if(addr == null || addr.length() == 0) return null;
		return addr;
	}

	private void openBluetoothSocket() {
		assert bt != null;
		new Thread() {

			@Override
			public void run() {
				try {
					Log.d(TAG, "Opening Bluetooth socket");
					btSocket = bt.listenUsingInsecureRfcommWithServiceRecord(
							SERVICE_NAME, SERVICE_UUID);
					Log.d(TAG, "Bluetooth socket opened");
					while(true) {
						BluetoothSocket s = btSocket.accept();
						final String addr = s.getRemoteDevice().getAddress();
						runOnUiThread(new Runnable() {
							public void run() {
								print("Incoming connection from " + addr);
							}
						});
						s.close();
					}
				} catch(IOException e) {
					Log.e(TAG, e.toString());
				}
			}
		}.start();
	}

	private void closeBluetoothSocket() {
		if(btSocket == null) return;
		Log.d(TAG, "Closing Blueooth socket");
		try {
			btSocket.close();
		} catch(IOException e) {
			Log.e(TAG, e.toString());
		}
	}

	private void connectByBluetooth(final String addr) {
		assert bt != null;
		if(!BluetoothAdapter.checkBluetoothAddress(addr)) {
			Log.d(TAG, addr + " is not a valid Bluetooth address");
			return;
		}
		final BluetoothDevice device = bt.getRemoteDevice(addr);
		print("Connecting to " + addr);
		new Thread() {

			@Override
			public void run() {
				try {
					BluetoothSocket s;
					s = device.createInsecureRfcommSocketToServiceRecord(
							SERVICE_UUID);
					s.connect();
					runOnUiThread(new Runnable() {
						public void run() {
							print("Connected to " + addr);
						}
					});
					s.close();
				} catch(IOException e) {
					Log.e(TAG, e.toString());
				}
			}
		}.start();
	}

	private void startDiscovery() {
		print("Starting discovery");
		startPeerDiscovery();
		addServiceRequest();
		startServiceDiscovery();
	}

	private void startPeerDiscovery() {
		p2p.discoverPeers(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Started peer discovery");
			}

			public void onFailure(int reason) {
				print("Starting peer discovery failed, error code " + reason);
			}
		});
	}

	private void addServiceRequest() {
		p2p.addServiceRequest(channel, serviceRequest, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Added service request");
			}

			public void onFailure(int reason) {
				print("Adding service request failed, error code " + reason);
			}
		});
	}

	private void startServiceDiscovery() {
		p2p.discoverServices(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Started service discovery");
			}

			public void onFailure(int reason) {
				print("Starting service discovery failed, error code "
						+ reason);
				if(reason == NO_SERVICE_REQUESTS) {
					// http://p2feed.com/wifi-direct.html#bugs
					stopDiscovery();
					startDiscovery();
				}
			}
		});
	}

	private void stopDiscovery() {
		print("Stopping discovery");
		stopPeerDiscovery();
		removeServiceRequest();
	}

	private void stopPeerDiscovery() {
		p2p.stopPeerDiscovery(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Stopped peer discovery");
			}

			public void onFailure(int reason) {
				print("Stopping peer discovery failed, error code " + reason);
			}
		});
	}

	private void removeServiceRequest() {
		p2p.removeServiceRequest(channel, serviceRequest, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Removed service request");
			}

			public void onFailure(int reason) {
				print("Removing service request failed, error code " + reason);
			}
		});
	}

	private class PeerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			Log.d(TAG, "Received intent: " + action);
			if(WIFI_P2P_PEERS_CHANGED_ACTION.equals(action))
				p2p.requestPeers(channel, peerListListener);
		}
	}
}
