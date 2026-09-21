Automated Bin Alert System

A serverless IoT pipeline simulating a smart dustbin. A software-only "bin" publishes fake sensor telemetry over MQTT to AWS IoT Core; the readings flow through Lambda into DynamoDB, and a scheduled Lambda automatically alerts via SNS when a bin needs collection, has a low battery, or has gone silent.

Built as a cloud computing elective project — no physical hardware required, everything is simulated in software.

Why this project

Waste collection is usually reactive — trucks run fixed routes regardless of whether a bin is actually full. This project explores the cloud-native alternative: bins report their own state, and the system only raises an alert when something actually needs attention. The same architecture also demonstrates a general pattern — ingesting device telemetry, storing it, and running automated anomaly detection over it — that applies well beyond waste management (application log aggregation, sensor networks, fleet monitoring, etc.).

Architecture
bin_sim.py ──MQTT──▶ AWS IoT Core ──rule──▶ ingest_lambda ──▶ DynamoDB
│
EventBridge (every 5 min)  │
│                   │
▼                   ▼
analysis_lambda ◀───── query
│
▼
SNS ──▶ email/SMS alert
Component	Role
bin_sim.py	Simulates a dustbin's sensors — publishes fill level and battery readings over MQTT
AWS IoT Core	Handles device auth and message routing (MQTT broker)
ingest_lambda	Triggered by an IoT Core rule on every message; writes the reading to DynamoDB
DynamoDB	Stores the raw time-series of readings, keyed by bin ID and timestamp
analysis_lambda	Triggered on a schedule; checks the latest reading against thresholds
EventBridge	Triggers the analysis Lambda every 5 minutes
SNS	Sends an email/SMS alert when a check fails
Features
Automated collection alerts — fires when fill level crosses a configurable threshold
Offline detection — flags a bin that hasn't reported in over N minutes (dead battery, lost connectivity)
Low battery alerts — separate threshold for battery level
Fully serverless — no servers to patch or manage; scales to zero when idle
No hardware required — the bin is a Python script; swap in a real ESP32 + ultrasonic sensor later without changing the cloud side
Tech stack
AWS IoT Core — MQTT ingestion and device management
AWS Lambda (Python 3.12) — ingest and analysis logic
Amazon DynamoDB — telemetry storage
Amazon SNS — alerting
Amazon EventBridge — scheduled triggers
Python 3.9+ — bin simulator (paho-mqtt)
Project structure
.
├── bin_sim.py                  # Simulated dustbin — publishes telemetry
├── lambdas/
│   ├── ingest_lambda.py        # Writes incoming readings to DynamoDB
│   └── analysis_lambda.py      # Scheduled check + SNS alerting
└── README.md
Getting started
Prerequisites
An AWS account
AWS CLI installed and configured (aws configure)
Python 3.9+
pip install paho-mqtt (only needed once you move past local mode)
1. Run the simulator locally

No AWS needed yet — just confirm the data looks right:

bash
python bin_sim.py --local
2. Create an IoT Thing and certificates
   bash
   aws iot create-thing --thing-name bin-001

aws iot create-keys-and-certificate \
--set-as-active \
--certificate-pem-outfile certs/device.pem.crt \
--public-key-outfile certs/public.pem.key \
--private-key-outfile certs/private.pem.key

curl -o certs/AmazonRootCA1.pem https://www.amazontrust.com/repository/AmazonRootCA1.pem

Create a policy and attach it to the certificate and Thing (see full commands in SETUP.md or the project write-up), then get your account's IoT endpoint:

bash
aws iot describe-endpoint --endpoint-type iot:Data-ATS
3. Connect the simulator to AWS
   bash
   python bin_sim.py \
   --endpoint <your-ats-endpoint> \
   --cert certs/device.pem.crt \
   --key certs/private.pem.key \
   --root-ca certs/AmazonRootCA1.pem
4. Deploy the ingest pipeline

Create a DynamoDB table (binId partition key, timestamp sort key), zip and deploy ingest_lambda.py, and create an IoT rule (SELECT * FROM 'bins/+/telemetry') that routes messages to it.

5. Deploy alerting

Create an SNS topic, subscribe your email, and deploy analysis_lambda.py on an EventBridge schedule (every 5 minutes), passing TABLE_NAME, SNS_TOPIC_ARN, BIN_ID, and your alert thresholds as environment variables.

Full step-by-step AWS CLI commands for every resource above are in the project setup notes.

Configuration

Environment variables for analysis_lambda.py:

Variable	Default	Description
TABLE_NAME	BinReadings	DynamoDB table name
SNS_TOPIC_ARN	(required)	Topic to publish alerts to
BIN_ID	bin-001	Which bin to check
FILL_THRESHOLD	80	Fill % that triggers a collection alert
SILENCE_MINUTES	15	Minutes without a reading before flagging offline
BATTERY_THRESHOLD	15	Battery % that triggers a low-battery alert
Testing
Full bin: let the simulator run until fill level naturally crosses the threshold, or temporarily lower FILL_THRESHOLD and redeploy
Silent bin: stop the simulator and wait past SILENCE_MINUTES
Low battery: temporarily raise BATTERY_THRESHOLD above 100 to force a test alert