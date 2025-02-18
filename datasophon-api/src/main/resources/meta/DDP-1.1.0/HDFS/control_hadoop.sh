#!/bin/bash
usage="Usage: start.sh (start|stop|restart) <command> "

# if no args specified, show usage
if [ $# -le 1 ]; then
  echo $usage
  exit 1
fi
startStop=$1
shift
command=$1

SH_DIR=`dirname $0`
echo $SH_DIR
ident=$SH_DIR/ident.id

if [[ "$command" = "namenode"  ||  "$command" = "datanode" || "$command" = "secondarynamenode" ||  "$command" = "journalnode" || "$command" = "zkfc" ]]; then
   cmd=$SH_DIR/bin/hdfs
elif [[ "$command" = "resourcemanager" || "$command" = "nodemanager" ]]; then
   cmd=$SH_DIR/bin/yarn
elif [[ "$command" = "historyserver" ]]; then
   cmd=$SH_DIR/bin/mapred
else
  echo "Error: No command named \'$command' was found."
  exit 1
fi

start(){
	echo "execute $cmd --daemon start $command"
	$cmd --daemon start $command
	if [ $? -eq 0 ]
    then
		echo "$command start success"
		if [ $command = "namenode" ]
	  then
		  echo "true" > $ident
		fi
	else
		echo "$command start failed"
		exit 1
	fi
}
stop(){
	$cmd --daemon stop $command
	if [ $? -eq 0 ]
    then
		echo "$command stop success"
	else
		echo "$command stop failed"
		exit 1
	fi
}
status(){
  echo "start check $command status"
	if [[ "$command" = "zkfc" ]];then
		pid=`jps | grep -iw DFSZKFailoverController | grep -v grep | awk '{print $1}'`
	elif [[ "$command" = "historyserver" ]]; then
		pid=`jps | grep -iw JobHistoryServer | grep -v grep | awk '{print $1}'`
	else
		pid=`jps | grep -iw $command | grep -v grep | awk '{print $1}'`
	fi
	echo "pid is : $pid"
	kill -0 $pid
	if [ $? -eq 0 ]
	then
		echo "$command is  running "
	else
		echo "$command  is not running"
		exit 1
	fi
}
restart(){
	stop
	sleep 10
	start
}
case $startStop in
  (start)
    start
    ;;
  (stop)
    stop
      ;;
  (status)
	  status
	;;
  (restart)
	  restart
      ;;
  (*)
    echo $usage
    exit 1
    ;;
esac


echo "End $startStop $command."