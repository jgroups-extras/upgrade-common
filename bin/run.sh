#!/bin/bash

DIR=`dirname $0`/../
DIR=`realpath $DIR`
SYSPROPS="-Djava.net.preferIPv4Stack=true"
TARGET="$DIR/target"
CP="$TARGET/lib/*:$TARGET/classes/"
#DEBUG="-agentlib:jdwp=transport=dt_socket,server=y,suspend=n,address=*:5005"

java $SYSPROPS $DEBUG -cp $CP $*

