package hk.zdl.crypto.tura.miner.util;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Timer;
import java.util.TimerTask;
import java.util.TreeMap;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.prefs.Preferences;

import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import org.apache.commons.io.IOUtils;
import org.json.JSONObject;
import org.json.JSONTokener;

import com.formdev.flatlaf.util.SystemInfo;
import com.profesorfalken.jsensors.JSensors;

import hk.zdl.crypto.tura.miner.MinerMonitor;
import hk.zdl.crypto.tura.miner.main.TuraConfig;

public class Util {
	public enum Auth {
		PASSWORD, PASSPHRASE, NONE
	}

	private static final String WALLET_LOCK_DATA = "WALLET_LOCK_DATA", IV = "WALLET_LOCK_IV";
	private static final String MY_SALT = "WALLET_LOCK_MY_SALT", MD5_HASH = "MD5_HASH", SHA_512_HASH = "SHA_512_HASH", AES_256 = "AES_256";

	private static File miner_bin = null;
	private static final ExecutorService es = Executors.newCachedThreadPool(r -> {
		Thread t = new Thread(r);
		t.setDaemon(true);
		t.setPriority(Thread.MIN_PRIORITY);
		return t;
	});
	private static Optional<Double> cpu_temp = Optional.empty();
	static {
		es.submit(() -> {
			if (SystemInfo.isLinux || SystemInfo.isWindows_10_orLater) {
				new Timer().scheduleAtFixedRate(new TimerTask() {

					@Override
					public void run() {
						cpu_temp = JSensors.get.components().cpus.stream().findAny().get().sensors.temperatures.stream().findAny().map(o -> o.value);
					}
				}, 0, 1000);
			}
		});
	}

	static {
		var p = Util.getUserSettings();
		synchronized (Util.class) {
			var iv = p.getByteArray(IV, null);
			var salt = p.getByteArray(MY_SALT, null);
			if (iv == null || salt == null) {
				try {
					iv = new byte[16];
					salt = new byte[32];
					var r = new Random();
					r.nextBytes(iv);
					r.nextBytes(salt);
					p.putByteArray(IV, iv);
					p.putByteArray(MY_SALT, salt);
					p.flush();
				} catch (Exception e) {
				}
			}
		}
	}

	public static synchronized MinerMonitor buildMinerProces(BigInteger id, String passphrase, List<Path> plot_dirs, URL server_url) throws Exception {
		var conf_file = LocalMiner.build_conf_file(id.toString(), passphrase, plot_dirs, server_url, null);
		if (miner_bin == null) {
			miner_bin = LocalMiner.copy_miner();
		}
		var proc = LocalMiner.build_process(miner_bin, conf_file);
		var mon = new MinerMonitor(proc);
		mon.set_conf_file(conf_file);
		return mon;
	}

	public static final Optional<Double> cpu_temp() {
		return cpu_temp;
	}

	public static final Map<String, Long> systemMemory() {
		Map<String, Long> map = new TreeMap<>();
		var rt = Runtime.getRuntime();
		long total_mem = rt.totalMemory();
		long free_mem = rt.freeMemory();
		long used_mem = total_mem - free_mem;
		map.put("total", total_mem);
		map.put("used", used_mem);
		map.put("free", free_mem);
		return map;
	}

	public static final Map<String, Map<String, Object>> diskUsage() {
		Map<String, Map<String, Object>> map = new TreeMap<>();
		try {
			Process process = new ProcessBuilder("df").start();
			IOUtils.readLines(process.getInputStream(), "UTF-8").stream().filter(s -> s.startsWith("/")).map(s -> s.split("\\s+")).forEach(s -> {
				Map<String, Object> m = new TreeMap<>();
				m.put("device", s[0]);
				m.put("size", Long.valueOf(s[1]));
				m.put("used", Long.valueOf(s[2]));
				m.put("avail", Long.valueOf(s[3]));
				m.put("ratio", s[4]);
				map.put(s[5], m);
			});
		} catch (IOException e) {
		}
		return map;
	}

	public static final int disk_temputure_cel(String device_path) throws Exception {
		if (!device_path.startsWith("/dev/sd")) {
			throw new IllegalArgumentException();
		}
		if (!TuraConfig.isRunningOnRoot()) {
			throw new IOException("must run in root");
		}
		if (!new File(device_path).exists()) {
			throw new IOException("device not exist");
		}
		return IOUtils.readLines(new ProcessBuilder("smartctl", "-a", device_path).start().inputReader()).stream().filter(s -> s.startsWith("194 Temperature_Celsius"))
				.map(s -> s.substring(s.lastIndexOf(' ') + 1)).mapToInt(Integer::parseInt).findAny().getAsInt();
	}

	public static final int isa_temputure_cel() throws Exception {
		return new JSONObject(new JSONTokener(new ProcessBuilder("sensors", "-j").start().getInputStream())).getJSONObject("coretemp-isa-0000").getJSONObject("Package id 0").getNumber("temp1_input")
				.intValue();
	}

	public static final int nvme_temputure_cel() throws Exception {
		var jobj = new JSONObject(new JSONTokener(new ProcessBuilder("sensors", "-j").start().getInputStream()));
		var opt = jobj.keySet().stream().filter(s -> s.startsWith("nvme-pci-")).findFirst();
		if (opt.isPresent()) {
			return jobj.getJSONObject(opt.get()).getJSONObject("Composite").getNumber("temp1_input").intValue();
		} else
			throw new FileNotFoundException();
	}

	public static final Callable<String> pingServer(String url) {
		return () -> {
			var host = new URL(url).getHost();
			var process = new ProcessBuilder("ping", "-c", "1", host).start();
			var lines = IOUtils.readLines(process.getInputStream(), Charset.defaultCharset());
			String str = lines.get(lines.size() - 1);
			str = str.substring(str.indexOf("=") + 1);
			str = str.substring(0, str.indexOf("/"));
			str = str.trim();
			return str;
		};
	}

	public static final Process plot(Path plot_bin, Path target, boolean benchmark, BigInteger id, long start_nonce, long nonces, PlotProgressListener listener)
			throws IOException, InterruptedException {
		if (!Files.exists(plot_bin.toAbsolutePath())) {
			plot_bin = findPath(plot_bin);
		}
		if (!plot_bin.toFile().exists()) {
			throw new FileNotFoundException(plot_bin.toString());
		} else if (!plot_bin.toFile().isFile()) {
			throw new FileNotFoundException("not a file: " + plot_bin.toString());
		} else if (!plot_bin.toFile().canRead()) {
			throw new IOException("cannot read: " + plot_bin.toString());
		} else if (!plot_bin.toFile().canExecute()) {
			throw new IOException("not executable: " + plot_bin.toString());
		}
		if (!target.toFile().exists()) {
			throw new FileNotFoundException(target.toString());
		} else if (!target.toFile().isDirectory()) {
			throw new IOException("not dir: " + target.toString());
		}
		var l = new LinkedList<String>();
		if (SystemInfo.isAARCH64) {
			var proc = new ProcessBuilder("docker", "run", "--privileged", "--rm", "tonistiigi/binfmt", "--install", "linux/amd64").start();
			int i = proc.waitFor();
			if (i != 0) {
				var err_info = IOUtils.readLines(proc.getErrorStream(), Charset.defaultCharset()).stream().reduce("", (a, b) -> a + "\n" + b).trim();
				throw new IOException(err_info);
			}
			l.addAll(Arrays.asList("docker", "run", "--platform", "linux/amd64", "--mount", "type=bind,source=" + plot_bin.toAbsolutePath().toString() + ",target=/app/signum-plotter", "--mount",
					"type=bind,source=" + target.toAbsolutePath().toString() + ",target=" + target.toAbsolutePath().toString(), "ubuntu", "/app/signum-plotter"));
		} else {
			l.add(plot_bin.toAbsolutePath().toString());
		}
		if (benchmark) {
			l.add("-b");
		}
		l.addAll(Arrays.asList("--id", id.toString(), "--sn", Long.toString(start_nonce), "--n", Long.toString(nonces), "-m", "2GiB", "-p", target.toAbsolutePath().toString()));
		var proc = new ProcessBuilder(l).start();
		var reader = proc.inputReader(Charset.defaultCharset());
		String line = null;
		while (true) {
			line = reader.readLine();
			if (line == null) {
				break;
			} else {
				line = line.trim();
			}
			if (line.isEmpty() || line.equals("[2A")) {
				continue;
			} else if (line.startsWith("Error: ")) {
				reader.close();
				throw new IOException(line.substring("Error: ".length()));
			} else if (line.equals("Starting plotting...")) {
				continue;
			} else {
				if (line.isEmpty() || line.equals("[2A")) {
					continue;
				} else {
					if (line.contains("鈹傗")) {
						byte[] bArr = line.getBytes("GBK");
						line = new String(bArr, "UTF-8");
						line = line.replace("�?", "│");
					}
				}
				if (line.startsWith("Hashing:") || line.startsWith("Writing:")) {
					var type = line.startsWith("H") ? PlotProgressListener.Type.HASH : PlotProgressListener.Type.WRIT;
					line = line.substring(line.lastIndexOf('│') + 1);
					float progress = Float.parseFloat(line.substring(0, line.indexOf('%')).trim());
					line = line.substring(line.indexOf('%') + 1).trim();
					String rate, eta = "";
					if (line.endsWith("B/s")) {
						rate = line;
					} else {
						rate = line.substring(0, line.lastIndexOf(" ")).trim().replace(" ", "");
						eta = line.substring(line.lastIndexOf(" ")).trim();
					}
					listener.onProgress(type, progress, rate, eta);
				}
			}

		}
		return proc;
	}

	private static final Path findPath(Path p) throws IOException {
		return IOUtils.readLines(new ProcessBuilder().command("which", p.toString()).start().getInputStream(), "UTF-8").stream().map(Paths::get).findFirst().get();
	}

	public static final Preferences getUserSettings() {
		return Preferences.userNodeForPackage(Util.class);
	}

	public static final Auth getAuthMethod() {
		return Auth.valueOf(getUserSettings().get(Auth.class.getSimpleName(), Auth.NONE.name()));
	}
	
	public static final void setAuthMethod(Auth a) {
		getUserSettings().put(Auth.class.getSimpleName(), a.name());
		try {
			getUserSettings().flush();
		} catch (Exception e) {
		}
	}

	public static final boolean hasPassword() {
		var s = getUserSettings().get(WALLET_LOCK_DATA, null);
		if (s == null || s.isBlank()) {
			return false;
		}
		try {
			new JSONObject(s);
		} catch (Throwable t) {
			return false;
		}
		return true;
	}

	public static boolean validete_password(char[] password) {
		var s = getUserSettings().get(WALLET_LOCK_DATA, null);
		if (s == null || s.isBlank()) {
			return false;
		}
		try {
			var jobj = new JSONObject(s);
			var enc = Base64.getEncoder();
			var barr = Charset.defaultCharset().encode(CharBuffer.wrap(password)).array();
			var md5_hash = enc.encodeToString(MessageDigest.getInstance("MD5").digest(barr));
			if (!md5_hash.equals(jobj.getString(MD5_HASH))) {
				return false;
			}
			var sha_hash = enc.encodeToString(MessageDigest.getInstance("SHA-256").digest(barr));
			if (!sha_hash.equals(jobj.getString(SHA_512_HASH))) {
				return false;
			}
			var aes_hash = enc.encodeToString(aes_encrypt(password, barr));
			if (!aes_hash.equals(jobj.getString(AES_256))) {
				return false;
			}
		} catch (Exception x) {
			return false;
		}
		return true;
	}

	public static final void store_new_pw(char[] new_pw) throws Exception {
		var enc = Base64.getEncoder();
		var barr = Charset.defaultCharset().encode(CharBuffer.wrap(new_pw)).array();
		var md5_hash = enc.encodeToString(MessageDigest.getInstance("MD5").digest(barr));
		var sha_hash = enc.encodeToString(MessageDigest.getInstance("SHA-256").digest(barr));
		var aes_hash = enc.encodeToString(aes_encrypt(new_pw, barr));
		var jobj = new JSONObject();
		jobj.put(MD5_HASH, md5_hash);
		jobj.put(SHA_512_HASH, sha_hash);
		jobj.put(AES_256, aes_hash);
		var p = getUserSettings();
		p.put(WALLET_LOCK_DATA, jobj.toString());
	}

	static final byte[] aes_encrypt(char[] pw, byte[] input) throws Exception {
		var p = getUserSettings();
		var iv = p.getByteArray(IV, null);
		var salt = p.getByteArray(MY_SALT, null);
		var ivspec = new IvParameterSpec(iv);
		var factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
		var spec = new PBEKeySpec(pw, salt, 65536, 256);
		var secretKey = new SecretKeySpec(factory.generateSecret(spec).getEncoded(), "AES");
		var cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
		cipher.init(Cipher.ENCRYPT_MODE, secretKey, ivspec);
		return cipher.doFinal(input);
	}

}
