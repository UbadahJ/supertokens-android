update-alternatives --set java /usr/java/jdk-12.0.2/bin/java
update-alternatives --set javac /usr/java/jdk-12.0.2/bin/javac
export JAVA_HOME=/usr/java/jdk-12.0.2/
export JRE_HOME=/usr/java/jdk-12.0.2/
export PATH=$PATH:$JAVA_HOME
java -version

frontendDriverJson=`cat ../frontendDriverInterfaceSupported.json`
frontendDriverLength=`echo $frontendDriverJson | jq ".versions | length"`
frontendDriverArray=`echo $frontendDriverJson | jq ".versions"`
echo "got frontend driver relations"

# get sdk version
version=`cat ../app/build.gradle | grep -e "publishVersionID =" -e "publishVersionID="`
while IFS="'" read -ra ADDR; do
    counter=0
    for i in "${ADDR[@]}"; do
        if [ $counter == 1 ]
        then
            version=$i
        fi
        counter=$(($counter+1))
    done
done <<< "$version"

responseStatus=`curl -s -o /dev/null -w "%{http_code}" -X PUT \
  https://api.supertokens.io/0/frontend \
  -H 'Content-Type: application/json' \
  -H 'api-version: 0' \
  -d "{
	\"password\": \"$SUPERTOKENS_API_KEY\",
	\"version\":\"$version\",
    \"name\": \"android\",
	\"frontendDriverInterfaces\": $frontendDriverArray
}"`
if [ $responseStatus -ne "200" ]
then
    echo "failed core PUT API status code: $responseStatus. Exiting!"
	exit 1
fi

someTestsRan=false
i=0
while [ $i -lt $frontendDriverLength ]; do
    frontendDriverVersion=`echo $frontendDriverArray | jq ".[$i]"`
    frontendDriverVersion=`echo $frontendDriverVersion | tr -d '"'`
    i=$((i+1))

    driverVersionXY=`curl -s -X GET \
    "https://api.supertokens.io/0/frontend-driver-interface/dependency/driver/latest?password=$SUPERTOKENS_API_KEY&mode=DEV&version=$frontendDriverVersion&driverName=node" \
    -H 'api-version: 0'`
    if [[ `echo $driverVersionXY | jq .driver` == "null" ]]
    then
        echo "fetching latest X.Y version for driver given frontend-driver-interface X.Y version: $frontendDriverVersion gave response: $driverVersionXY. Please make sure all relevant drivers have been pushed."
        git push --delete origin dev-v$version
        exit 1
    fi
    driverVersionXY=$(echo $driverVersionXY | jq .driver | tr -d '"')

    driverInfo=`curl -s -X GET \
    "https://api.supertokens.io/0/driver/latest?password=$SUPERTOKENS_API_KEY&mode=DEV&version=$driverVersionXY&name=node" \
    -H 'api-version: 0'`
    if [[ `echo $driverInfo | jq .tag` == "null" ]]
    then
        echo "fetching latest X.Y.Z version for driver, X.Y version: $driverVersionXY gave response: $driverInfo"
        git push --delete origin dev-v$version
        exit 1
    fi
    driverTag=$(echo $driverInfo | jq .tag | tr -d '"')
    driverVersion=$(echo $driverInfo | jq .version | tr -d '"')

    git clone https://github.com/supertokens/supertokens-node.git
    cd supertokens-node
    git checkout $driverTag
    coreDriverJson=`cat ./coreDriverInterfaceSupported.json`
    coreDriverLength=`echo $coreDriverJson | jq ".versions | length"`
    coreDriverArray=`echo $coreDriverJson | jq ".versions"`
    coreDriverVersion=`echo $coreDriverArray | jq ".[0]"`
    coreDriverVersion=`echo $coreDriverVersion | tr -d '"'`
    cd ../
    rm -rf supertokens-node

    coreCommercial=`curl -s -X GET \
    "https://api.supertokens.io/0/core-driver-interface/dependency/core/latest?password=$SUPERTOKENS_API_KEY&planType=COMMERCIAL&mode=DEV&version=$coreDriverVersion" \
    -H 'api-version: 0'`
    if [[ `echo $coreCommercial | jq .core` == "null" ]]
    then
        echo "fetching latest X.Y version for core given core-driver-interface X.Y version: $coreDriverVersion, planType: COMMERCIAL gave response: $coreCommercial. Please make sure all relevant cores have been pushed."
        git push --delete origin dev-v$version
        exit 1
    fi
    coreCommercial=$(echo $coreCommercial | jq .core | tr -d '"')

    someTestsRan=true
    ./setupAndTestWithCommercialCore.sh $coreCommercial $driverTag
    if [[ $? -ne 0 ]]
    then
        echo "test failed... exiting!"
        git push --delete origin dev-v$version
        exit 1
    fi
    cd .circleci/
    rm -rf ../../com-root
    rm -rf ../testHelpers/server/node_modules/supertokens-node
    git checkout HEAD -- ../testHelpers/server/package.json
done

if [[ $someTestsRan = "true" ]]
then
    echo "calling /frontend PATCH to make testing passed"
    responseStatus=`curl -s -o /dev/null -w "%{http_code}" -X PATCH \
        https://api.supertokens.io/0/frontend \
        -H 'Content-Type: application/json' \
        -H 'api-version: 0' \
        -d "{
            \"password\": \"$SUPERTOKENS_API_KEY\",
            \"version\":\"$version\",
            \"name\": \"android\",
            \"testPassed\": true
        }"`
    if [ $responseStatus -ne "200" ]
    then
        echo "patch api failed"
        exit 1
    fi
else
    echo "no test ran"
    exit 1
fi