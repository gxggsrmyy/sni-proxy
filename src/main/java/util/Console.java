package util;

import java.text.SimpleDateFormat;
import java.util.Date;

public class Console {
    public static boolean debugMode = false;
    private static SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss");

    public static void debug(String module, String str){
        if(debugMode){
            System.out.println("[DEBUG " + module + " " + sdf.format(new Date()) + "] " + str);
        }
    }

    public static void info(String module, String str){
        System.out.println("[INFO " + module + " " + sdf.format(new Date()) + "] " + str);
    }
    public static void warn(String module, String str){
        System.out.println("[WARN " + module + " " + sdf.format(new Date()) + "] " + str);
    }
    public static void error(String module, String str){
        System.out.println("[ERROR " + module + " " + sdf.format(new Date()) + "] " + str);
    }
}
