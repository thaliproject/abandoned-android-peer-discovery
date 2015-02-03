package org.briarproject.bonjour;

import static android.net.wifi.p2p.WifiP2pManager.EXTRA_WIFI_P2P_DEVICE;
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
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.UnknownHostException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
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
import android.os.Handler;
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
	private BluetoothAdapter bt;
	private Channel channel;
	private BroadcastReceiver receiver;
	private IntentFilter filter;
	private PeerListListener peerListListener;
	private DnsSdTxtRecordListener txtListener;
	private DnsSdServiceResponseListener serviceListener;

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

		openWifiSocket();

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
				Log.d(TAG, "Discovered peers:");
				for(WifiP2pDevice peer : peers.getDeviceList())
					Log.d(TAG, "\t" + deviceToString(peer));
			}
		};

		serviceListener = new DnsSdServiceResponseListener() {

			public void onDnsSdServiceAvailable(String instanceName,
					String serviceType, WifiP2pDevice device) {
				Log.d(TAG, "Discovered service:");
				Log.d(TAG, "\t" + instanceName + " " + serviceType);
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
				if(domain.endsWith("." + SERVICE_TYPE + ".local.")) {
					String ip = txtMap.get("ip");
					if(ip != null) connectByWifi(ip);
					String bt = txtMap.get("bt");
					if(bt != null) connectByBluetooth(bt);
				}
			}
		};

		p2p.setDnsSdResponseListeners(channel, serviceListener, txtListener);

		String instanceName = "foo";
		Map<String, String> txtMap = new HashMap<String, String>();
		InetAddress ipAddr = getLocalIpAddress();
		if(ipAddr != null) txtMap.put("ip", ipAddressToString(ipAddr));
		String btAddr = getLocalBluetoothAddress();
		if(btAddr != null) txtMap.put("bt", btAddr);
		serviceInfo = WifiP2pDnsSdServiceInfo.newInstance(instanceName,
				SERVICE_TYPE, txtMap);

		startDiscovery();
	}

	@Override
	public void onResume() {
		super.onResume();
		registerReceiver(receiver, filter);
		printLocalIpAddresses();
	}

	@Override
	public void onPause() {
		super.onPause();
		unregisterReceiver(receiver);
	}

	@Override
	public void onDestroy() {
		super.onDestroy();
		stopDiscovery();
		closeWifiSocket();
		if(bt != null) {
			closeBluetoothSocket();
			disableBluetooth();
		}
	}

	public void onClick(View view) {
		if(view == refresh) printLocalIpAddresses();
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
		return device.deviceName + " " + device.deviceAddress;
	}

	private void printLocalIpAddresses() {
		List<NetworkInterface> ifaces;
		try {
			ifaces = Collections.list(NetworkInterface.getNetworkInterfaces());
		} catch(SocketException e) {
			Log.e(TAG, e.toString());
			return;
		}
		print("Local IP addresses:");
		for(NetworkInterface iface : ifaces) {
			for(InetAddress addr : Collections.list(iface.getInetAddresses())) {
				String desc = ipAddressToString(addr);
				if(addr.isLoopbackAddress()) desc += " (loopback)";
				if(addr.isLinkLocalAddress()) desc += " (link-local)";
				if(addr.isSiteLocalAddress()) desc += " (site-local)";
				if(addr.isMulticastAddress()) desc += " (multicast)";
				print("\t" + iface.getName() + ": " + desc);
			}
		}
	}

	private InetAddress getLocalIpAddress() {
		WifiManager wifi = (WifiManager) getSystemService(WIFI_SERVICE);
		if(wifi == null) return null;
		WifiInfo info = wifi.getConnectionInfo();
		if(info == null || info.getNetworkId() == -1) return null;
		int ipInt = info.getIpAddress(); // What if it's an IPv6 address?
		byte[] ip = ipIntToBytes(ipInt);
		try {
			return InetAddress.getByAddress(ip);
		} catch(UnknownHostException e) {
			Log.e(TAG, e.toString());
			return null;
		}
	}

	private byte[] ipIntToBytes(int ip) {
		byte[] b = new byte[4];
		b[0] = (byte) (ip & 0xFF);
		b[1] = (byte) ((ip >> 8) & 0xFF);
		b[2] = (byte) ((ip >> 16) & 0xFF);
		b[3] = (byte) ((ip >> 24) & 0xFF);
		return b;
	}

	private String ipAddressToString(InetAddress ip) {
		return ip.getHostAddress().replaceFirst("%.*", "");
	}

	private boolean isValidIpAddress(String ip) {
		boolean v4 = InetAddressUtils.isIPv4Address(ip);
		boolean v6 = InetAddressUtils.isIPv6Address(ip);
		if(!v4 && !v6) return false;
		try {
			InetAddress inet = InetAddress.getByName(ip);
			return inet.isLinkLocalAddress() || inet.isSiteLocalAddress();
		} catch(UnknownHostException e) {
			Log.e(TAG, e.toString());
			return false;
		}
	}

	private void openWifiSocket() {
		final InetAddress ip = getLocalIpAddress();
		if(ip == null) return;
		new Thread() {

			@Override
			public void run() {
				try {
					Log.d(TAG, "Opening wifi socket");
					ServerSocket socket = new ServerSocket();
					socket.bind(new InetSocketAddress(ip, PORT));
					Log.d(TAG, "Wifi socket opened");
					wifiSocket = socket;
					while(true) {
						Socket s = socket.accept();
						InetAddress remoteIp = s.getInetAddress();
						final String addr = ipAddressToString(remoteIp);
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
					final String local = ipAddressToString(s.getLocalAddress());
					runOnUiThread(new Runnable() {
						public void run() {
							print("Connected to " + ip + " from " + local);
						}
					});
					s.close();
				} catch(IOException e) {
					Log.e(TAG, e.toString());
				}
			}
		}.start();
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
		addLocalService();
	}

	private void addLocalService() {
		p2p.addLocalService(channel, serviceInfo, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Added local service");
				startPeerDiscovery();
			}

			public void onFailure(int reason) {
				print("Adding local service failed, error code " + reason);
				startPeerDiscovery();
			}
		});
	}

	private void startPeerDiscovery() {
		p2p.discoverPeers(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Started peer discovery");
				addServiceRequest();
			}

			public void onFailure(int reason) {
				print("Starting peer discovery failed, error code " + reason);
				addServiceRequest();
			}
		});
	}

	private void addServiceRequest() {
		WifiP2pDnsSdServiceRequest request =
				WifiP2pDnsSdServiceRequest.newInstance();
		final Handler handler = new Handler();
		p2p.addServiceRequest(channel, request, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Added service request");
				// Calling discoverServices() too soon can result in a
				// NO_SERVICE_REQUESTS failure - looks like a race condition
				// http://p2feed.com/wifi-direct.html#bugs
				handler.postDelayed(new Runnable() {
					public void run() {
						startServiceDiscovery();
					}
				}, 1000);
			}

			public void onFailure(int reason) {
				print("Adding service request failed, error code " + reason);
				// No point starting service discovery
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
			}
		});
	}

	private void stopDiscovery() {
		print("Stopping discovery");
		stopPeerDiscovery();
	}

	private void stopPeerDiscovery() {
		p2p.stopPeerDiscovery(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Stopped peer discovery");
				clearServiceRequests();
			}

			public void onFailure(int reason) {
				print("Stopping peer discovery failed, error code " + reason);
			}
		});
	}

	private void clearServiceRequests() {
		p2p.clearServiceRequests(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Cleared service requests");
				clearLocalServices();
			}

			public void onFailure(int reason) {
				print("Clearing service requests failed, error code " + reason);
			}
		});
	}

	private void clearLocalServices() {
		p2p.clearLocalServices(channel, new ActionListener() {

			public void onSuccess() {
				Log.d(TAG, "Cleared local services");
			}

			public void onFailure(int reason) {
				print("Clearing local services failed, error code " + reason);
			}
		});
	}

	private class PeerReceiver extends BroadcastReceiver {

		@Override
		public void onReceive(Context context, Intent intent) {
			String action = intent.getAction();
			Log.d(TAG, "Received intent: " + action);
			if(WIFI_P2P_PEERS_CHANGED_ACTION.equals(action)) {
				p2p.requestPeers(channel, peerListListener);
			} else if(WIFI_P2P_THIS_DEVICE_CHANGED_ACTION.equals(action)) {
				WifiP2pDevice device = intent.getParcelableExtra(
						EXTRA_WIFI_P2P_DEVICE);
				Log.d(TAG, "Local device: " + deviceToString(device));
			}
		}
	}
}
