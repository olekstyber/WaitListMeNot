//Author: Oleksandr Tyberkevych
//Date: Dec. 11, 2013

//WaitlistMeNot is a system that continuously checks for seat availability of University of Michigan courses in order to provide
//a rapid way to respond to an opening in a wait-listed or closed enrollment class. It may simultaneously check many courses; however, 
//due to restrictions of UofM API, it may only make 60 requests per minute. If it finds an opening in the course listing, the system
//will inform the user by playing a distress sound, allowing him to enroll into the course before other students notice an open spot.

package mainPkg;

import java.applet.Applet;
import java.applet.AudioClip;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Writer;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Timer;
import java.util.TimerTask;
import java.io.OutputStreamWriter;

import org.restlet.engine.util.Base64;

public class WaitlistMeNot {
	
	private static String consumerKey = "REDACTED";				//ConsumerKey provided by UofM API services.
	private static String consumerSecret = "REDACTED";				//ConsumerSecret provided by UofM API services.
	volatile private static String accessToken;										//Used to authenticate a request over REST.
	volatile private static String refreshToken;										//Used for authentication renewal.
	private static String[] tokens;													//Structure that contains accessToken in [0] and refreshToken in [1].
	private static String tokenKey;													//A key generated from ConsumerKey and ConsumerSecret.
	private static ArrayList<Integer> classesMonitored = new ArrayList<Integer>();    //Contains the list of monitored course IDs.
	private static int term;															//Contains the term number for monitoring.
	private static Boolean isRinging;
	
	public static void main(String[] args) throws IOException {
		//Args: [0]: term, [1]...[n]: course ID's.
		//Check whether a correct number of arguments is given to the program. If not, terminate the program and inform the user.
		if(args.length <= 1){
			System.out.println("Not enough arguments were given to the program. First argument is the course term, followed by all the " +
					           "classes that you would like to monitor.");
			return;
		}
		
		//Check whether all arguments are numeric.
		for(int i = 0; i < args.length; i++){
			if(!args[i].matches("[0-9]+")){
				System.out.println("One of your arguments (\"" + args[i] + "\" is not a number.");
				return;
			}
		}
		
		//Parse the program arguments.
		term = Integer.parseInt(args[0]);
		for(int i = 1; i < args.length; i++){
			classesMonitored.add(Integer.parseInt(args[i]));
		}
		
		//Initialize the access tokens.
		if(!init()){
			System.out.println("Error during initialization. Check your consumer key/secret and the availability of the UofM API server.");
			return; 
		}
		
		// Every refreshTime milliseconds, refresh the current access token.
		int refreshTime = 10*60*1000;				//10 minutes in this case. Generally, a given token expires in 60 minutes, however
													//it is best to decrease the renewal time to a lower value to account for communication
													//issues with the server.
		Timer timerTokenRefresh = new Timer();
		timerTokenRefresh.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				try {
					//Refresh the token array and the access/refreshTokens. If there was an error while trying to renew (like an
					//'unauthorized' error code for example), the function will terminate without changing the
					//values of access and refresh tokens. 
					tokens = refreshAccToken(tokenKey, refreshToken);
				} catch (IOException e) {
					e.printStackTrace();
				}
				accessToken = tokens[0];
				refreshToken = tokens[1];
			}
		}, refreshTime, refreshTime);

		// Every accessTime milliseconds, request new data from the UofM course registry and check whether the class(es) have any new openings.
		int accessTime = 200500; 
		Timer timerAccess = new Timer();
		timerAccess.scheduleAtFixedRate(new TimerTask() {
			@Override
			public void run() {
				for(int i = 0; i < classesMonitored.size(); i++){
					try {
						int seats = getOpenSeats(term, classesMonitored.get(i), accessToken);
						//If there are open seats, play a sound to inform the user of the event. 
						if(seats > 0 && !isRinging){
							//isRinging = true;
						    Runnable runner = new Runnable(){
								public void run() {
									AudioClip clip = Applet.newAudioClip(
									this.getClass().getClassLoader().getResource("alarm_sound.wav"));
									clip.play();
								}
							};
						    Thread soundThread = (new Thread(runner));
						    soundThread.start();		
						    //soundThread.join();
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}, 0, accessTime);
		
		
	}
	
	//The init function is called once per run. It initializes the program by generating a special tokenKey which is used to receive a new
	//access token from UofM API server. It assigns the initial values for access and refresh tokens. It assumes that consumerKey and
	//consumerSecret are already defined. It returns true if everything went fine and false otherwise.
	static Boolean init() throws IOException{
		isRinging = false;
		//Generate a new tokenKey for refreshing/getting access tokens.
		tokenKey = consumerKey + ":" + consumerSecret;
		tokenKey = Base64.encode(tokenKey.getBytes(), false);

		//Get a new token and its corresponding refresh token.
		tokens = getNewAccToken(tokenKey);
		if(tokens.length != 2 || (tokens[0] == null || tokens[1] == null) || tokens == null) return false;
		accessToken = tokens[0];
		refreshToken = tokens[1];
		
		return true;
	}
	
	//The getNewAccToken function takes in a tokenKey generated by the init function and attempts to generate a new access and
	//refresh tokens by POSTing to the UofM API server. It assumes that the program has already been initialized. Return is in the format
	//of String[0] = accessToken, String[1] = refreshToken.
	static String[] getNewAccToken(String tokenKey) throws IOException {
		//Set the correct properties of REST request. These were inferred from
		//UofM API site.
		String authUrlStr = "https://api-km.it.umich.edu/token";
		URL authUrl = new URL(authUrlStr);
		HttpURLConnection authConn = (HttpURLConnection) authUrl
				.openConnection();
		authConn.setRequestMethod("POST");
		authConn.setDoOutput(true);
		authConn.setDoInput(true);
		authConn.setUseCaches(false);
		authConn.setAllowUserInteraction(false);
		authConn.setRequestProperty("Authorization", "Basic " + tokenKey);
		authConn.setRequestProperty("Content-Type",
				"application/x-www-form-urlencoded");
		//POST the grant_type and scope.
		OutputStream out = authConn.getOutputStream();
		Writer writer = new OutputStreamWriter(out, "UTF-8");
		writer.write("grant_type=client_credentials&scope=PRODUCTION");
		writer.close();
		out.close();
		//If return code is not 200 (good request), then inform the user and throw exception.
		if (authConn.getResponseCode() != 200) {
			throw new IOException(authConn.getResponseMessage());
		}
		
		// Get result.
		BufferedReader rd = new BufferedReader(new InputStreamReader(
				authConn.getInputStream()));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = rd.readLine()) != null) {
			sb.append(line);
		}
		rd.close();
		authConn.disconnect();
		
		// Process the response into a refresh and access tokens.
		String response = sb.toString();
		String[] responseSplit = response.split("\"");
		System.out.println(response);
		String token = responseSplit[13];
		System.out.println(token);
		String refreshToken = responseSplit[9];
		System.out.println(refreshToken);
		String[] tokens = { token, refreshToken };
		return tokens;

	}
	
	//This function is used to refresh an authentication token given a tokenKey generated in init() and a refreshToken that
	//is generated every time a token is received/refreshed. It returns a string array with the same representation as for
	//getNewAcctToken() function. 
	static String[] refreshAccToken(String tokenKey, String refreshToken) throws IOException {
		//Properly set up UofM API request guidelines for renewing a token.
		String authUrlStr = "https://api-km.it.umich.edu/token";
		URL authUrl = new URL(authUrlStr);
		HttpURLConnection authConn = (HttpURLConnection) authUrl.openConnection();
		authConn.setRequestMethod("POST");
		authConn.setDoOutput(true);
		authConn.setDoInput(true);
		authConn.setUseCaches(false);
		authConn.setAllowUserInteraction(false);

		authConn.setRequestProperty("Authorization", "Basic " + tokenKey);
		authConn.setRequestProperty("Content-Type",
				"application/x-www-form-urlencoded");

		OutputStream out = authConn.getOutputStream();
		Writer writer = new OutputStreamWriter(out, "UTF-8");
		writer.write("grant_type=refresh_token&refresh_token=" + refreshToken
				+ "&scope=PRODUCTION");
		writer.close();
		out.close();

		if (authConn.getResponseCode() != 200) {
			throw new IOException(authConn.getResponseMessage());
		}

		// Buffer the result into a string.
		BufferedReader rd = new BufferedReader(new InputStreamReader(
				authConn.getInputStream()));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = rd.readLine()) != null) {
			sb.append(line);
		}
		rd.close();
		authConn.disconnect();
		// Process the response into a refresh and access tokens.
		String response = sb.toString();
		String[] responseSplit = response.split("\"");
		System.out.println(response);
		String token = responseSplit[13];
		System.out.println(token);
		String newRefreshToken = responseSplit[9];
		System.out.println(newRefreshToken);
		String[] tokens = { token, newRefreshToken };
		return tokens;
	}

	//Given a term, classID, and an accessToken for UofM API, this function returns the number of open seats available for the class.
	//It assumes that all the previous steps for authentication have been completed and that the accessToken is valid. Otherwise it throws
	//an IOException. 
	static int getOpenSeats(int term, int classID, String accessToken) throws IOException{
		int openSeats = -1;
		//Format the request URL with the term and classID.
		String urlStr = "http://api-gw.it.umich.edu/Curriculum/SOC/v1/Terms/" + term + "/Classes/" + classID;
		URL url = new URL(urlStr);
		HttpURLConnection conn = (HttpURLConnection) url.openConnection();
		conn.setRequestProperty("Authorization", "Bearer " + accessToken);

		if (conn.getResponseCode() != 200) {
			throw new IOException(conn.getResponseMessage());
		}

		//Get the result.
		BufferedReader rd = new BufferedReader(new InputStreamReader(conn.getInputStream()));
		StringBuilder sb = new StringBuilder();
		String line;
		while ((line = rd.readLine()) != null) {
			sb.append(line);
		}
		rd.close();

		conn.disconnect();
		String[] availSeatsStrArr = sb.toString().split(":");
		//If the returned data is for some reason in an unknown format, return -1 to signify an error.
		if(availSeatsStrArr.length < 22) return -1;
		String availSeatsStr = availSeatsStrArr[21].split(",")[0];
		availSeatsStr = availSeatsStr.substring(1, availSeatsStr.length()-1);
		System.out.println(classID + ": " + availSeatsStr);
		openSeats = Integer.parseInt(availSeatsStr);
		
		return openSeats;
	}
}
