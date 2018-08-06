# CLOUDERA-BUILD
export JAVA7_BUILD=true
. /opt/toolchain/toolchain.sh

export MAVEN_OPTS="-Xms512m -Xmx1024m -XX:PermSize=256m -XX:MaxPermSize=512m"
mvn clean test
