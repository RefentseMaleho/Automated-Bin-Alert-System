Automated Bin Alert System
A serverless IoT pipeline simulating a smart dustbin. A software-only "bin" publishes fake sensor telemetry over MQTT to AWS IoT Core; the readings flow through an AWS Lambda into DynamoDB, and a scheduled Lambda automatically alerts via SNS when a bin needs collection, has a low battery, or has gone silent.

Java / Maven implementation, built as a multi-module project so it opens cleanly in IntelliJ.

Project structure
automated-bin-alert-system/          <- parent (open THIS folder in IntelliJ)
├── pom.xml                          <- parent POM, lists the 3 modules
├── bin-simulator/                   <- MODULE 1: the simulated dustbin
│   ├── pom.xml
│   └── src/main/java/com/refentse/binalert/simulator/
│       ├── BinSimulator.java        <- main class, entry point
│       ├── BinReading.java          <- one telemetry sample
│       └── BinState.java            <- fill/battery simulation logic
├── ingest-lambda/                   <- MODULE 2: writes readings to DynamoDB
│   ├── pom.xml
│   └── src/main/java/com/refentse/binalert/
│       ├── ingest/IngestHandler.java
│       └── model/BinReading.java    <- POJO Lambda deserializes JSON into
├── analysis-lambda/                 <- MODULE 3: scheduled checks + SNS alerts
│   ├── pom.xml
│   └── src/main/java/com/refentse/binalert/analysis/
│       └── AnalysisHandler.java
└── README.md
Each module is independently buildable and deployable — bin-simulator runs on your machine, ingest-lambda and analysis-lambda get zipped and deployed to AWS Lambda separately.

Opening the project in IntelliJ
File → Open, select the automated-bin-alert-system folder (the one containing the parent pom.xml) — not one of the module subfolders.
IntelliJ will detect it's a Maven project and prompt to import; accept, or if it doesn't prompt, right-click pom.xml → Add as Maven Project.
Give it a minute to resolve dependencies (bottom-right progress bar). You'll see all three modules appear in the Project panel once it's done.
If IntelliJ asks about a Project SDK, point it at JDK 17 or newer (File → Project Structure → Project SDK).
No manual module creation needed — the folder structure and POMs above already define everything; IntelliJ just reads it in.

Step 1 — Run the simulator locally (no AWS yet)
In IntelliJ: open BinSimulator.java, right-click → Modify Run Configuration, set Program arguments to --local, then run.

Or from the command line:

cd bin-simulator
mvn clean package
java -jar target/bin-simulator-1.0.0.jar --local
You should see JSON readings print every 5 seconds. Confirm the shape looks right before moving on.

Step 2 — Create an IoT Thing and get certificates
aws iot create-thing --thing-name bin-001

aws iot create-keys-and-certificate \
--set-as-active \
--certificate-pem-outfile certs/device.pem.crt \
--public-key-outfile certs/public.pem.key \
--private-key-outfile certs/private.pem.key

curl -o certs/AmazonRootCA1.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem

cat > iot-policy.json << 'EOF'
{
"Version": "2012-10-17",
"Statement": [
{ "Effect": "Allow", "Action": "iot:Connect", "Resource": "*" },
{ "Effect": "Allow", "Action": "iot:Publish", "Resource": "*" }
]
}
EOF
aws iot create-policy --policy-name BinSimPolicy --policy-document file://iot-policy.json

# Replace <certificateArn> with the ARN printed by create-keys-and-certificate
aws iot attach-policy --policy-name BinSimPolicy --target <certificateArn>
aws iot attach-thing-principal --thing-name bin-001 --principal <certificateArn>

aws iot describe-endpoint --endpoint-type iot:Data-ATS
Test the connection with the MQTT test client in the IoT Core console (subscribe to bins/+/telemetry), then run:

java -jar target/bin-simulator-1.0.0.jar \
--endpoint <your-ats-endpoint> \
--cert certs/device.pem.crt \
--key certs/private.pem.key \
--root-ca certs/AmazonRootCA1.pem
You should see your readings appear live in the console.

Step 3 — DynamoDB table + ingest Lambda
aws dynamodb create-table \
--table-name BinReadings \
--attribute-definitions \
AttributeName=binId,AttributeType=S \
AttributeName=timestamp,AttributeType=S \
--key-schema \
AttributeName=binId,KeyType=HASH \
AttributeName=timestamp,KeyType=RANGE \
--billing-mode PAY_PER_REQUEST

# Build the fat jar
cd ingest-lambda
mvn clean package
# -> target/ingest-lambda.jar

# Execution role
cat > trust-policy.json << 'EOF'
{
"Version": "2012-10-17",
"Statement": [{
"Effect": "Allow",
"Principal": { "Service": "lambda.amazonaws.com" },
"Action": "sts:AssumeRole"
}]
}
EOF
aws iam create-role --role-name BinIngestRole --assume-role-policy-document file://trust-policy.json
aws iam attach-role-policy --role-name BinIngestRole \
--policy-arn arn:aws:iam::aws:policy/service-role/AWSLambdaBasicExecutionRole
aws iam attach-role-policy --role-name BinIngestRole \
--policy-arn arn:aws:iam::aws:policy/AmazonDynamoDBFullAccess

# Deploy (replace <account-id>)
aws lambda create-function \
--function-name binIngest \
--runtime java17 \
--handler com.refentse.binalert.ingest.IngestHandler::handleRequest \
--zip-file fileb://target/ingest-lambda.jar \
--role arn:aws:iam::<account-id>:role/BinIngestRole \
--timeout 15 \
--memory-size 512 \
--environment "Variables={TABLE_NAME=BinReadings}"

# IoT rule routing telemetry to this Lambda
cd ..
cat > rule.json << 'EOF'
{
"sql": "SELECT * FROM 'bins/+/telemetry'",
"actions": [{
"lambda": { "functionArn": "arn:aws:lambda:<region>:<account-id>:function:binIngest" }
}]
}
EOF
aws iot create-topic-rule --rule-name BinTelemetryRule --topic-rule-payload file://rule.json

aws lambda add-permission \
--function-name binIngest \
--statement-id iot-invoke \
--action lambda:InvokeFunction \
--principal iot.amazonaws.com
Java Lambdas have a slower cold start than Python (JVM startup) — the --memory-size 512 and --timeout 15 above give it enough headroom. This is a normal Java-on-Lambda tradeoff, not a bug.

Run the simulator again, then check the table:

aws dynamodb scan --table-name BinReadings
Step 4 — SNS + scheduled analysis Lambda
aws sns create-topic --name BinAlerts
# note the TopicArn, then:
aws sns subscribe --topic-arn <TopicArn> --protocol email --notification-endpoint you@example.com
# confirm the subscription email

cd analysis-lambda
mvn clean package
# -> target/analysis-lambda.jar

aws iam attach-role-policy --role-name BinIngestRole \
--policy-arn arn:aws:iam::aws:policy/AmazonSNSFullAccess

aws lambda create-function \
--function-name binAnalysis \
--runtime java17 \
--handler com.refentse.binalert.analysis.AnalysisHandler::handleRequest \
--zip-file fileb://target/analysis-lambda.jar \
--role arn:aws:iam::<account-id>:role/BinIngestRole \
--timeout 15 \
--memory-size 512 \
--environment "Variables={TABLE_NAME=BinReadings,SNS_TOPIC_ARN=<TopicArn>,BIN_ID=bin-001,FILL_THRESHOLD=80,SILENCE_MINUTES=15,BATTERY_THRESHOLD=15}"

cd ..
aws events put-rule --name BinCheckSchedule --schedule-expression "rate(5 minutes)"

aws lambda add-permission \
--function-name binAnalysis \
--statement-id eventbridge-invoke \
--action lambda:InvokeFunction \
--principal events.amazonaws.com \
--source-arn arn:aws:events:<region>:<account-id>:rule/BinCheckSchedule

aws events put-targets --rule BinCheckSchedule \
--targets "Id"="1","Arn"="arn:aws:lambda:<region>:<account-id>:function:binAnalysis"
Testing the alerts
Full bin: run the simulator until fill level crosses 80%, or temporarily set FILL_THRESHOLD to 10 on the Lambda and redeploy.
Silent bin: stop the simulator, wait past SILENCE_MINUTES.
Low battery: temporarily raise BATTERY_THRESHOLD above 100 to force a test alert.
Cost note
IoT Core, Lambda, DynamoDB (on-demand), SNS, and EventBridge all sit within AWS's free tier for typical class-project usage. Set a Billing budget alert before deploying, and don't leave the simulator publishing continuously for weeks — watch IoT Core's monthly message allowance if you do.

Cleanup (when you're done)
aws events remove-targets --rule BinCheckSchedule --ids "1"
aws events delete-rule --name BinCheckSchedule
aws lambda delete-function --function-name binAnalysis
aws lambda delete-function --function-name binIngest
aws iot delete-topic-rule --rule-name BinTelemetryRule
aws dynamodb delete-table --table-name BinReadings
aws sns delete-topic --topic-arn <TopicArn>
# detach/delete the IoT cert, policy and Thing, and delete the IAM role