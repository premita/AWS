<?xml version="1.0" encoding="US-ASCII" ?>
<xsl:stylesheet version="2.0" xmlns:xsl="http://www.w3.org/1999/XSL/Transform"
    xmlns:att="http://att.com">
  <!-- Root template -->
  <xsl:output method="html"/>
  <xsl:template match="/att:EmailContract">
    <html lang="en-US">
    <head>
    </head>
    <body>
    <img src="http://owg-aus-q100.corp.wayport.net:8870/images/ATT_Wi-Fi_Solutions.png" alt="AT&amp;T"></img>
    <div style="line-height:17px;">
     <font style="font-family: Calibri,Verdana, Arial, Helvetica, sans-serif;font-size: 11px">
    <p>Administrator of <xsl:value-of select="concat(normalize-space(att:Organization/att:Name),',')"/></p>
    <p>This message is to notify you that AT&amp;T Wi-Fi Solutions received a request to create a Central Command account with access to <xsl:value-of select="att:Organization/att:Name"/>'s Information.
    </p>
    <p>Please view the request and approve if the user is allowed to have access to <xsl:value-of select="att:Organization/att:Name"/>'s data.
    Otherwise, please disapprove the request. Click here to review the account request:
    <a href="{concat(att:BaseWebsiteUrl,'/identity/faces/request?key=',normalize-space(att:RequestID))}">View Request</a>.
    </p>
    
    <p>User requesting access:
    
    
    <table style="font-family: Calibri,Verdana, Arial, Helvetica, sans-serif;font-size: 11px">
    <tr>
    <td width="30px"> </td><td width="125px">First Name:</td><td><xsl:value-of select="att:Recipient/att:FirstName"/></td>
    </tr>
    <tr>
    <td width="30px"> </td><td width="125px">Last Name:</td><td><xsl:value-of select="att:Recipient/att:LastName"/></td>
    </tr>
    <tr>
    <td width="30px"> </td><td width="125px">Organization:</td><td><xsl:value-of select="att:Organization/att:Name"/></td>
    </tr>
    
    </table>
    </p>
    <p>
	Your AT&amp;T Wi-Fi Solutions Team<br/>
	<center><xsl:text disable-output-escaping="yes">&amp;#169;</xsl:text> 2014 AT&amp;T Intellectual Property.  All rights reserved.  AT&amp;T, the AT&amp;T logo and all other AT&amp;T marks contained herein are trademarks of AT&amp;T Intellectual Property and/or AT&amp;T affiliated companies.</center><br/>
        <center><font style="color:#c4bc96;font-size:90%">Please do not reply to this message; this mailbox is not monitored.</font></center>
    </p>
    </font>
    </div>
    </body>
    </html>

  </xsl:template>   
</xsl:stylesheet>
