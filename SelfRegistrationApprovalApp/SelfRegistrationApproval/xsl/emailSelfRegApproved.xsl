<?xml version="1.0" encoding="US-ASCII" ?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:att="http://att.com">
  <!-- Root template -->
  <xsl:output method="html"/>
  <xsl:template match="/att:EmailContract">
    <html lang="en-US">
    <head>
    </head>
    <body><div style="line-height:17px;">
    <font style="font-family: Calibri,Verdana, Arial, Helvetica, sans-serif;font-size: 11px">
    <p><xsl:value-of select="concat(att:Recipient/att:FirstName, ' ', att:Recipient/att:LastName,',')"/></p>
   
    <p>Your request for an AT&amp;T Wi-Fi Solutions account has been approved:</p>
    <p>Your User Name is <strong><xsl:value-of select="att:Recipient/att:UserLogin"/></strong>.</p>
   
    
    <p>For any issues, please contact your designated Administrator at 
    <a href="mailto:AWSWSPDEV_Account_Support@attwifi.com">AWSWSPDEV_Account_Support@wattwifi.com</a>
    .</p>
    
    <p>Your AT&amp;T Wi-Fi Solutions Team<br/>
    <font style="color:#c4bc96;font-size:90%">Please do not reply to this message; this mailbox is not monitored.</font><br/>
         </p>
    </font>
    </div>
    </body>
    </html>

  </xsl:template>   
</xsl:stylesheet>
