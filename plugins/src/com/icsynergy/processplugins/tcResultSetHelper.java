package com.icsynergy.helpers;

import Thor.API.tcResultSet;

import java.util.logging.Logger;

public class tcResultSetHelper {
    
    private static final Logger logger = Logger.getLogger("com.icsynergy");
    
    public static void printResultSet( tcResultSet res, String str, Logger logger ) {
        StringBuilder strRes = new StringBuilder(str + "\n");

        try {
            for( int i=0; i<res.getRowCount(); i++) {
                strRes.append("------------------------------------------------------\n");
                res.goToRow(i);
                for( String strCol : res.getColumnNames() ) {
                    strRes.append(strCol).append(" = ").append(res.getStringValue(strCol)).append("\n");
                }
            }
            logger.finest(strRes.toString());
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
