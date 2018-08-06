#!/bin/sh

# Licensed to the Apache Software Foundation (ASF) under one
# or more contributor license agreements.  See the NOTICE file
# distributed with this work for additional information
# regarding copyright ownership.  The ASF licenses this file
# to you under the Apache License, Version 2.0 (the
# "License"); you may not use this file except in compliance
# with the License.  You may obtain a copy of the License at
#
#     http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

# This script is used to generate the annotation of package info that
# records the version, revision, branch, url, user and timestamp.

unset LANG
unset LC_CTYPE
unset LC_TIME
version=$1
buildDirectory=$2
revision=$3
user=`whoami`
date=`date`
dir=`pwd`
cwd=`dirname $dir`
if [ -n "${COMPONENT_HASH}" ]; then
  revision="${COMPONENT_HASH}"
  url="http://github.com/cloudera/flume-ng"
  branch="Unkown"
elif [ -d ../.svn ]; then
  if [ "$revision" = "" ]; then
    revision=`svn info ../ | sed -n -e 's/Last Changed Rev: \(.*\)/\1/p'`
  fi
  url=`svn info  ../ | sed -n -e 's/URL: \(.*\)/\1/p'`
  branch=`echo $url | sed -n -e 's,.*\(branches/.*\)$,\1,p' \
                             -e 's,.*\(tags/.*\)$,\1,p' \
                             -e 's,.*trunk$,trunk,p'`
elif git rev-parse HEAD 2>/dev/null > /dev/null ; then
  if [ "$revision" = "" ]; then
    revision=`git log -1 --pretty=format:"%H"`
  fi
  hostname=`hostname`
  branch=`git branch | sed -n -e 's/^* //p'`
  url="git://${hostname}${cwd}"
else
  if [ "$revision" = "" ]; then
    revision="Unknown"
  fi
  branch="Unknown"
  url="file://$cwd"
fi

if [ -n "$(which md5sum)" ]; then
  srcChecksum=`find ../ -name '*.java' | grep -v generated-sources | LC_ALL=C sort | \
      xargs md5sum | md5sum | cut -d ' ' -f 1`
else
  srcChecksum=`find ../ -name '*.java' | grep -v generated-sources | LC_ALL=C sort | \
      xargs md5 | md5 | cut -d ' ' -f 1`
fi

mkdir -p $buildDirectory/generated-sources/java/org/apache/flume/
cat << EOF | \
  sed -e "s/VERSION/$version/" -e "s/USER/$user/" -e "s/DATE/$date/" \
      -e "s|URL|$url|" -e "s/REV/$revision/" \
      -e "s|BRANCH|$branch|" -e "s/SRCCHECKSUM/$srcChecksum/" \
      > $buildDirectory/generated-sources/java/org/apache/flume/package-info.java
/*
 * Generated by scripts/saveVersion.sh
 */
@VersionAnnotation(version="VERSION", revision="REV", branch="BRANCH",
                         user="USER", date="DATE", url="URL",
                         srcChecksum="SRCCHECKSUM")
package org.apache.flume;
EOF
