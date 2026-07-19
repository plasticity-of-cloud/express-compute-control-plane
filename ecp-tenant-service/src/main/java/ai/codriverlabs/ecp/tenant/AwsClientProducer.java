package ai.codriverlabs.ecp.tenant;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import software.amazon.awssdk.services.ec2.Ec2Client;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.secretsmanager.SecretsManagerClient;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.cloudwatchevents.CloudWatchEventsClient;

@ApplicationScoped
public class AwsClientProducer {

    @Produces @ApplicationScoped
    Ec2Client ec2Client() { return Ec2Client.create(); }

    @Produces @ApplicationScoped
    KmsClient kmsClient() { return KmsClient.create(); }

    @Produces @ApplicationScoped
    SecretsManagerClient secretsManagerClient() { return SecretsManagerClient.create(); }

    @Produces @ApplicationScoped
    SqsClient sqsClient() { return SqsClient.create(); }

    @Produces @ApplicationScoped
    CloudWatchEventsClient cloudWatchEventsClient() { return CloudWatchEventsClient.create(); }
}
