#!/prod/app/oracle/iammiddleware/oracle_common/common/bin/wlst.sh                                                             
connect()
grantPermission(codeBaseURL="file:${domain.home}/lib/Csf.jar", permClass="oracle.security.jps.service.credstore.CredentialAccessPermission", permTarget="context=SYSTEM,mapName=oim,keyName=*", permActions="read")