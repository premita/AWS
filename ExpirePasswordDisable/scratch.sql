select usr_key, usr_login, usr_pwd_expire_date, usr_status from usr where usr_login is not null and usr_pwd_expire_date is not null;

select count (usr_key) from usr where usr_first_name = 'BK Restaurant';

select * from usr;

select * from plugins;

select act_key,act_name from act where act_name='Hilton';

select * from usr where usr_login='CHUDSON3' or usr_key=4;

