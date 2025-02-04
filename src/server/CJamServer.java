package server;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Properties;
import java.util.logging.ConsoleHandler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import processing.core.PApplet;
import processing.net.Client;
import processing.net.Server;
import client.CJam;

import com.jogamp.common.net.asset.Handler;
import com.jogamp.opengl.util.packrect.Level;

public class CJamServer extends PApplet {
	private static final long serialVersionUID = -4544033159723138250L;

	private Server server;
	private Client client;

	protected static String mainPath;
	// where the java&class files of the clients go
	private static String blobPath;
	/**
	 * here are the blob-codes(format that gets into the MainCanvas) These are
	 * just txt files-since they dont run on their own
	 */
	private static String innerClassPath;
	private static String setupFilesPath;
	private static File mainCanvasTxt;
	private static File mainCanvasJava;

	private static int port = 30303;

	private boolean writeStandAlone = true;
	private boolean deleteOldInnerClasses = true;
	private boolean deleteOldStandalones = true;

	// default-names are blob_ipAddress(where _ is used instead of .)
	HashMap<String, String> ipToName = new HashMap<>();

	private final String nl = System.getProperty("line.separator");

	private static Logger log = Logger.getGlobal();

	/**
	 * wait until this number of clients submitted they sketch until the canvas
	 * is updated (and restarted)
	 */
	private int canvasUdpateRate = 1;

	ArrayList<String> submits = new ArrayList<String>();

	/**
	 * If clients have submitted their sketch (but rate is not) wait most until
	 * updateTimeout for canvas update
	 */
	private int updateTimeout = 30000;
	private int updateTimer = 0;

	/**
	 * set when the MainCanvas is running and needs to be destroyed
	 */
	private static boolean MCRunning = false;

	/**
	 * this is the process running the MainCanvasAdd
	 */
	private Process process;

	@Override
	public void setup() {
		super.setup();
		size(1, 1);
		setFolders();
		setSettings();
		setupLogger();

		server = new Server(this, port);
		try {
			log.info("CJamServer running at port:" + port + " "
					+ InetAddress.getLocalHost());
		} catch (UnknownHostException e) {
			e.printStackTrace();
		}
		if (deleteOldInnerClasses)
			deleteFilesIn(innerClassPath);
		if (deleteOldStandalones)
			deleteFilesIn(blobPath);
	}

	private void setSettings() {
		Properties properties = new Properties();
		try {
			properties.load(new FileReader(new File(mainPath
					+ "server-setup.properties")));
			port = Integer.valueOf(properties.getProperty("port"));
			deleteOldInnerClasses = Boolean.valueOf(properties
					.getProperty("deleteOldBlobs"));
			writeStandAlone = Boolean.valueOf(properties
					.getProperty("writeStandAlone"));
			deleteOldStandalones = Boolean.valueOf(properties
					.getProperty("deleteOldStandalones"));
			canvasUdpateRate = Integer.valueOf(properties
					.getProperty("canvasUdpateRate"));
			updateTimeout = Integer.valueOf(properties
					.getProperty("updateTimeout"));
		} catch (FileNotFoundException e) {
			System.err
					.println("Propertiesfile not found. staying with default");
		} catch (IOException e) {
			e.printStackTrace();
			e.printStackTrace();
		} catch (NumberFormatException nfe) {
			System.err.println(nfe.getMessage() + nl
					+ "Staying with the good old");
		}
	}

	private void deleteFilesIn(String path) {
		File[] oldFiles = new File(path).listFiles();
		System.out.println(path);
		for (File f : oldFiles) {
			log.fine(f.getName() + "delted");
			f.delete();
		}
	}

	public static void setFolders() {
		mainPath = new File("").getAbsolutePath(); // .substring( 0, p.length( )
													// - 3 );
		log.info("mainpath: " + mainPath);
		String separator = File.separator;
		blobPath = mainPath + separator + "blobs" + separator;
		createPathIfNotThere(blobPath);
		setupFilesPath = mainPath + separator + "setupFiles" + separator;
		createPathIfNotThere(setupFilesPath);
		innerClassPath = mainPath + separator + "innerClasses" + separator;
		System.out.println("cool " + innerClassPath);
		createPathIfNotThere(innerClassPath);
		mainCanvasTxt = new File(setupFilesPath + "mainCanvas.txt");
		mainCanvasJava = new File(mainPath + separator + "src" + separator
				+ "server" + separator + "MainCanvas.java");
	}

	private static void createPathIfNotThere(String pathName) {
		File path = new File(pathName);
		if (!path.exists())
			try {
				path.createNewFile();
			} catch (IOException e) {
				e.printStackTrace();
			}
	}

	@Override
	public void draw() {
		client = server.available();
		if (client != null) {
			String s = client.readStringUntil(CJam.msgEndMarker);
			if (s != null) {
				clientProcess(client, s.substring(0, s.length() - 1));
			}
		}
		TimoutUpdate();
	}

	/**
	 * checks if the timerupdate is expired to restart the canvas
	 */
	private void TimoutUpdate() {
		if (submits.size() >= 1 && (millis() - updateTimer) >= updateTimeout) {
			updateMainCanvas();
			submits.clear();
		}
	}

	public void clientProcess(Client client, String msg) {
		String ip = client.ip();
		log.info("Client msg: " + ip + "\n" + msg);
		// println("received: " + cs);
		// println(lines.length);
		if (msg.startsWith("name:")) {
			ipToName.put(ip, msg.substring(5));
			File oldBlobClass = new File("blob_" + ip.replaceAll("\\.", "_"));
			if (oldBlobClass.exists())
				oldBlobClass.delete();
			return;
		} else if (!ipToName.containsKey(ip)) {
			String name = "blob_" + ip.replaceAll("\\.", "_");
			log.info("new Client: " + name);
			ipToName.put(ip, name);
		}
		String name = ipToName.get(ip);
		File innerClassFile = new File(innerClassPath + name + ".txt");
		String[] lines = msg.split("\n");
		try {
			BufferedWriter innerClassWriter = new BufferedWriter(
					new FileWriter(innerClassFile));
			BufferedWriter standaloneWriter = null;
			if (writeStandAlone) {
				File standaloneBlob = new File(blobPath + name + ".java");
				BufferedReader standaloneTemplateReader = new BufferedReader(
						new FileReader(setupFilesPath
								+ "BlobJavaImportLines.txt"));
				standaloneWriter = new BufferedWriter(new FileWriter(
						standaloneBlob));
				String l;
				while ((l = standaloneTemplateReader.readLine()) != null)
					standaloneWriter.write(l + nl);
				standaloneTemplateReader.close();
				standaloneWriter.write("public class " + name
						+ " extends PApplet {" + nl);
			}
			innerClassWriter.write("public class " + name
					+ " extends CJamBlob {" + nl);
			for (String line : lines) {
				if (line.contains(CJam.delMarker))
					continue;
				innerClassWriter.write(line);
				if (writeStandAlone)
					standaloneWriter.write(line);
			}
			innerClassWriter.write(nl);
			innerClassWriter.write("}");
			innerClassWriter.close();
			if (writeStandAlone) {
				standaloneWriter.write(nl);
				standaloneWriter.write("}");
				standaloneWriter.close();
			}
			log.info(name + " txtfile written!");
			server.disconnect(client);
			if (submitRateReached(name))
				updateMainCanvas();
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	private void updateMainCanvas() {
		try {
			FileReader reader = new FileReader(mainCanvasTxt);
			FileWriter fw = new FileWriter(mainCanvasJava);
			// write from the mainCanvas txt file (template into the java file)
			int read = 0;
			while ((read = reader.read()) != -1)
				fw.write(read);
			reader.close();
			// some additional line to load the inner classes (no reflection)
			File[] blobFiles = new File(innerClassPath).listFiles();
			fw.write("	private CJamBlob[] loadBlobs() { "
					+ nl
					+ "System.out.println(getClass().getSuperclass().getName());"
					+ nl
					+ "int n = getClass().getSuperclass().getDeclaredClasses().length;"
					+ nl + "CJamBlob[] blobs = new CJamBlob[n];" + nl);
			int i = 0;
			for (File blob : blobFiles) {
				String blobName = blob.getName();
				blobName = blobName.substring(0, blobName.length() - 4);
				fw.write("blobs[" + (i++) + "] = new " + blobName + "();" + nl);
			}
			fw.write("return blobs;}" + nl);
			// add the blobs as inner classes
			for (File blob : blobFiles) {
				log.info("adding: " + blob);
				reader = new FileReader(blob);
				while ((read = reader.read()) != -1)
					fw.write(read);
				reader.close();
			}
			fw.write(nl + "}" + nl);
			fw.close();
		} catch (IOException e) {
			e.printStackTrace();
		}
		startMC();
	}

	private void startMC() {
		System.out.println("compiling");
		ArrayList<File> files = new ArrayList<File>();
		File f1 = new File(mainPath + File.separator + "src" + File.separator
				+ "server" + File.separator + "MainCanvas.java");
		File f2 = new File(mainPath + File.separator + "src" + File.separator
				+ "server" + File.separator + "MainCanvasAdd.java");

		System.out.println(f1);
		System.out.println(f2);

		// System.exit( 1 );

		files.add(f1);
		files.add(f2);
		boolean success = new Compiler().compile(files);
		System.out.println("compilation: " + success);
		if (!success) {
			System.err.println("Compilation failed. Actual Canvas remains");
			return;
		}
		if (MCRunning)
			process.destroy();

		String p = "java -cp " + mainPath + File.separator + "bin;" + mainPath
				+ File.separator + "bin" + File.separator + "core.jar "
				+ "server.MainCanvasAdd";
		System.out.println(p);
		try {
			process = Runtime.getRuntime().exec(p);
			MCRunning = true;
		} catch (IOException e) {
			e.printStackTrace();
		}
	}

	/**
	 * checks if the number of submits reached canvasUdpateRate. Multiple
	 * submits by the same client will be registered and does not increate the
	 * number of submits
	 *
	 * @param clientName
	 * @return true, if rate is reached
	 */
	private boolean submitRateReached(String clientName) {
		String timeS = ((updateTimeout - (millis() - updateTimer)) / 1000)
				+ "s";
		if (submits.contains(clientName)) {
			log.info(clientName + " updated. Timer expires in " + timeS);
			return false;
		}
		submits.add(clientName);
		if (submits.size() >= canvasUdpateRate) {
			submits.clear();
			return true;
		} else if (submits.size() == 1) {
			updateTimer = millis();
			log.info("1/" + canvasUdpateRate + " starting updateTimer: "
					+ (updateTimeout / 1000) + "s");
		} else {
			log.info(submits.size() + "/" + canvasUdpateRate
					+ " timer expires in: " + timeS);
		}
		return false;
	}

	private void setupLogger() {
		log.setLevel(java.util.logging.Level.ALL);
		log.setFilter(null);
		log.setUseParentHandlers(false);
		ConsoleHandler h = new ConsoleHandler();
		h.setFormatter(new SimpleFormatter() {

			@Override
			public String format(LogRecord record) {
				return record.getMessage() + " @T: " + time(record.getMillis())
						+ "\n";
			}

			private String time(long millisecs) {
				SimpleDateFormat date_format = new SimpleDateFormat("HH:mm");
				Date resultdate = new Date(millisecs);
				return date_format.format(resultdate);
			}
		});
		log.addHandler(h);
	}

	public static void main(String[] args) {
		PApplet.main(new String[] { "server.CJamServer" });
	}
}
