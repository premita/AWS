package com.icsynergy.helpers;

import Thor.API.Exceptions.tcAPIException;
import Thor.API.tcResultSet;

import java.util.logging.Logger;

public class tcResultSetHelper {
    
    private static final String TAG = "tcResultSetHelper";
    private static final Logger logger = Logger.getLogger("com.icsynergy");
    
    public static void printResultSet( tcResultSet res, String str, Logger logger ) {
        String strRes = str + "\n";

        try {
            for( int i=0; i<res.getRowCount(); i++) {
                strRes += "------------------------------------------------------\n";
                res.goToRow(i);
                for( String strCol : res.getColumnNames() ) {
                    strRes += strCol + " = " + res.getStringValue(strCol) + "\n";
                }
            }
            logger.finest(strRes);
        } catch (Exception e) {
            logger.severe(e.toString());
        }
    }
    
    public static int containsString( tcResultSet res, String strColumName, String strValue ) {
        try {
            for( int i=0; i<res.getRowCount(); i++ ) {
            res.goToRow(i);
            if( res.getStringValue(strColumName).equalsIgnoreCase(strValue) )
                return i;
        }
        } catch (Exception e) {
            logger.severe(e.toString());
            return -1;
        }
        return -1;
    }
}
