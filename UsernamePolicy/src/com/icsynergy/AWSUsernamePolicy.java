package com.icsynergy;

import java.util.Locale;
import java.util.Map;

import oracle.core.ojdl.logging.ODLLogger;

import oracle.iam.identity.exception.UserNameGenerationException;
import oracle.iam.identity.usermgmt.api.UserNamePolicy;
import oracle.iam.identity.usermgmt.utils.UserNameGenerationUtil;
import oracle.iam.identity.usermgmt.utils.UserNamePolicyUtil;

public class AWSUsernamePolicy implements UserNamePolicy {
    
    private static final ODLLogger m_logger = ODLLogger.getODLLogger("com.icsynergy");
    private static final String TAG = "AWSUsernamePolicy";
    
    public AWSUsernamePolicy() {
        super();
    }

    public String getUserNameFromPolicy(Map<String, String> map) throws UserNameGenerationException {
        m_logger.entering( TAG, "getUserNameFromPolicy" );
        
        String userName = null;

        String firstName = map.get("First Name");
        String lastName = map.get("Last Name");
        m_logger.finest("firstName: "+firstName);
        m_logger.finest("lastName: "+lastName);
        
        // Ensure the first and last names are not null or empty and first char is valid
        if ((firstName == null) || (firstName.length() == 0) || (!Character.isLetter(firstName.charAt(0)))) {

            UserNameGenerationException exception = new UserNameGenerationException("First Name is Invalid", "INVALIDFIRSTNAME");
            throw exception;
        }

        if ((lastName == null) || (lastName.length() == 0) || (!Character.isLetter(lastName.charAt(0)))) {
            UserNameGenerationException exception = new UserNameGenerationException("Last Name is Invalid", "INVALIDLASTNAME");
            throw exception;
        }
        
        // Adds first initial and last initial to the username
        String firstInitial = firstName.substring(0, 1);
        String lastInitial = lastName.substring(0, 1);
        userName = firstInitial.concat(lastInitial).toUpperCase();
        m_logger.finest("Username: "+userName);
        
        userName = userName.concat(generateUsernameSuffix());
        m_logger.finest("Username: "+userName);
        
        if (UserNameGenerationUtil.isUserNameExistingOrReserved(userName)) {
            String baseName = userName.substring(0,2);
            boolean isInUse = true;
            int maxAttempts = 26000;

            while (isInUse && maxAttempts > 0) {
                userName = baseName.concat(generateUsernameSuffix());
                if (UserNameGenerationUtil.isUserNameExistingOrReserved(userName)) {
                    maxAttempts--;
                    if(maxAttempts%10==0)
                        m_logger.finest("Attempts Remaining: "+maxAttempts);
                    continue;
                }
                isInUse = false;
            }

            if (isInUse) {
                throw new UserNameGenerationException("Failed To Generate Unique User Name", "GENERATEUSERNAMEFAILED");
            }
            if(maxAttempts<5200)
                m_logger.severe("Namespace for base "+baseName+" is nearly full!");
        }
        
        
        m_logger.exiting( TAG, "getUserNameFromPolicy" );
        return userName;
    }
    
    private String generateUsernameSuffix(){
        m_logger.entering( TAG, "generateUsernameSuffix" );
        String unameSuffix = "";
        // Generate four random numbers to add to the username
        for(int x=0;x<4;x++) {
            int randNum = ((int)(Math.random()*10));
            unameSuffix = unameSuffix.concat(String.valueOf(randNum));
        }
        m_logger.finest("unameSuffix: "+unameSuffix);
        
        // Generate one random char to add to the username
        int randCharNum = ((int)(Math.random()*26))+65;
        unameSuffix = unameSuffix + ((char)randCharNum);
        m_logger.finest("unameSuffix: "+unameSuffix);
        m_logger.exiting( TAG, "generateUsernameSuffix" );
        return unameSuffix;
    }

    public boolean isUserNameValid(String userName, Map<String, String> map) {
        m_logger.entering( TAG, "isUserNameValid" );
        for (int x = 0; x < userName.length(); x++) {
            if (!Character.isLetterOrDigit(userName.charAt(x))) {
                m_logger.finest("FALSE");
                m_logger.exiting( TAG, "isUserNameValid" );
                return false;
            }
        }
        m_logger.finest("TRUE");
        m_logger.exiting( TAG, "isUserNameValid" );
        return true;
    }

    public String getDescription(Locale locale) {
        return "AWS Custom Username Policy: FI + LI + 4 Random # + 1 Random char";
    }
}
