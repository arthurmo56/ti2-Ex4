package com.example;

import com.azure.identity.DefaultAzureCredential;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.ai.textanalytics.TextAnalyticsClient;
import com.azure.ai.textanalytics.TextAnalyticsClientBuilder;
import com.azure.ai.textanalytics.models.*;
import com.azure.ai.textanalytics.util.*;


import java.io.IOException;
import java.util.*;

public class Main {

    static String personGroupId = UUID.randomUUID().toString();

    // URL path for the images.
    private static final String IMAGE_BASE_URL = "https://raw.githubusercontent.com/Azure-Samples/cognitive-services-sample-data-files/master/Face/images/";

    // From your Face subscription in the Azure portal, get your subscription key and endpoint.
    private static final String SUBSCRIPTION_KEY = System.getenv("VISION_KEY");
    private static final String ENDPOINT = System.getenv("VISION_ENDPOINT");

    public static void main(String[] args) {
        // Recognition model 4 was released in February 2021.
        // It is recommended since its accuracy is improved
        // on faces wearing masks compared with model 3,
        // and its overall accuracy is improved compared
        // with models 1 and 2.
        final String RECOGNITION_MODEL4 = RecognitionModel.RECOGNITION_04;

        // Authenticate.
        IFaceClient client = Authenticate(ENDPOINT, SUBSCRIPTION_KEY);

        // Identify - recognize a face(s) in a person group (a person group is created in this example).
        try {
            IdentifyInPersonGroup(client, IMAGE_BASE_URL, RECOGNITION_MODEL4).get();
        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("End of quickstart.");
    }

    /*
     * AUTHENTICATE
     * Uses subscription key and region to create a client.
     */
    public static IFaceClient Authenticate(String endpoint, String key) {
        return FaceClient.builder().endpoint(endpoint).credential(new ApiKeyCredentials(key)).buildClient();
    }

    // Detect faces from image url for recognition purposes. This is a helper method for other functions in this quickstart.
    // Parameter `returnFaceId` of `DetectWithUrlAsync` must be set to `true` (by default) for recognition purposes.
    // Parameter `FaceAttributes` is set to include the QualityForRecognition attribute. 
    // Recognition model must be set to recognition_03 or recognition_04 as a result.
    // Result faces with insufficient quality for recognition are filtered out. 
    // The field `faceId` in returned `DetectedFace`s will be used in Face - Face - Verify and Face - Identify.
    // It will expire 24 hours after the detection call.
    private static List<DetectedFace> DetectFaceRecognize(IFaceClient faceClient, String url, String recognition_model) throws IOException {
        // Detect faces from image URL. Since only recognizing, use recognition model 1.
        // We use detection model 3 because we are not retrieving attributes.
        List<DetectedFace> sufficientQualityFaces = new ArrayList<>();
        List<DetectedFace> detectedFaces = faceClient.face().detectWithUrl(url,
                new DetectWithUrlOptionalParameter().withRecognitionModel(recognition_model)
                        .withDetectionModel(DetectionModel.DETECTION_03).withReturnFaceAttributes(Collections.singletonList(FaceAttributeType.QUALITY_FOR_RECOGNITION)));
        for (DetectedFace detectedFace : detectedFaces) {
            Double faceQualityForRecognition = detectedFace.faceAttributes().qualityForRecognition();
            if (faceQualityForRecognition != null && faceQualityForRecognition >= QualityForRecognition.MEDIUM) {
                sufficientQualityFaces.add(detectedFace);
            }
        }
        System.out.printf("%d face(s) with %d having sufficient quality for recognition detected from image `%s`%n", detectedFaces.size(), sufficientQualityFaces.size(), url);

        return sufficientQualityFaces;
    }

    /*
     * IDENTIFY FACES
     * To identify faces, you need to create and define a person group.
     * The Identify operation takes one or several face IDs from DetectedFace or PersistedFace and a PersonGroup and returns 
     * a list of Person objects that each face might belong to. Returned Person objects are wrapped as Candidate objects, 
     * which have a prediction confidence value.
     */
    public static void IdentifyInPersonGroup(IFaceClient client, String url, String recognitionModel) throws Exception {
        System.out.println("========IDENTIFY FACES========");
        System.out.println();

        // Create a dictionary for all your images, grouping similar ones under the same key.
        Map<String, String[]> personDictionary = new HashMap<>();
        personDictionary.put("Family1-Dad", new String[]{"Family1-Dad1.jpg", "Family1-Dad2.jpg"});
        personDictionary.put("Family1-Mom", new String[]{"Family1-Mom1.jpg", "Family1-Mom2.jpg"});
        personDictionary.put("Family1-Son", new String[]{"Family1-Son1.jpg", "Family1-Son2.jpg"});
        personDictionary.put("Family1-Daughter", new String[]{"Family1-Daughter1.jpg", "Family1-Daughter2.jpg"});
        personDictionary.put("Family2-Lady", new String[]{"Family2-Lady1.jpg", "Family2-Lady2.jpg"});
        personDictionary.put("Family2-Man", new String[]{"Family2-Man1.jpg", "Family2-Man2.jpg"});

        // A group photo that includes some of the persons you seek to identify from your dictionary.
        String sourceImageFileName = "identification1.jpg";

        // Create a person group.
        System.out.printf("Create a person group (%s).%n", personGroupId);
        client.personGroup().create(personGroupId, personGroupId, null, null, recognitionModel).get();
        // The similar faces will be grouped into a single person group person.
        for (String groupedFace : personDictionary.keySet()) {
            // Limit TPS
            Thread.sleep(250);
            Person person = client.personGroupPerson().create(personGroupId, groupedFace, null, null).get();
            System.out.printf("Create a person group person '%s'.%n", groupedFace);

            // Add face to the person group person.
            for (String similarImage : personDictionary.get(groupedFace)) {
                System.out.println("Check whether image is of sufficient quality for recognition");
                List<DetectedFace> detectedFaces1 = DetectFaceRecognize(client, url + similarImage, recognitionModel);
                boolean sufficientQuality = true;
                for (DetectedFace face1 : detectedFaces1) {
                    Double faceQualityForRecognition = face1.faceAttributes().qualityForRecognition();
                    // Only "high" quality images are recommended for person enrollment
                    if (faceQualityForRecognition != null && faceQualityForRecognition != QualityForRecognition.HIGH) {
                        sufficientQuality = false;
                        break;
                    }
                }

                if (!sufficientQuality) {
                    continue;
                }

                // add face to the person group
                System.out.printf("Add face to the person group person(%s) from image `%s`%n", groupedFace, similarImage);
                client.personGroupPerson().addFaceFromUrl(personGroupId, person.personId(), url + similarImage, similarImage).get();
            }
        }

        // Start to train the person group.
        System.out.println();
        System.out.printf("Train person group %s.%n", personGroupId);
        client.personGroup().train(personGroupId).get();

        // Wait until the training is completed.
        while (true) {
            Thread.sleep(1000);
            TrainingStatus trainingStatus = client.personGroup().getTrainingStatus(personGroupId).get();
            System.out.printf("Training status: %s.%n", trainingStatus.status());
            if (trainingStatus.status() == TrainingStatusType.SUCCEEDED) {
                break;
            }
        }
        System.out.println();

        List<UUID> sourceFaceIds = new ArrayList<>();
        // Detect faces from source image url.
        List<DetectedFace> detectedFaces = DetectFaceRecognize(client, url + sourceImageFileName, recognitionModel);

        // Add detected faceId to sourceFaceIds.
        for (DetectedFace detectedFace : detectedFaces) {
            sourceFaceIds.add(detectedFace.faceId());
        }

        // Identify the faces in a person group.
        IdentifyResult[] identifyResults = client.face().identifyInPersonGroup(personGroupId, sourceFaceIds.toArray(new UUID[0]), null, null).get();

        for (IdentifyResult identifyResult : identifyResults) {
            if (identifyResult.candidates().isEmpty()) {
                System.out.printf("No person is identified for the face in: %s - %s%n", sourceImageFileName, identifyResult.faceId());
                continue;
            }
            UUID personId = identifyResult.candidates().get(0).personId();
            Person person = client.personGroupPerson().get(personGroupId, personId).get();
            System.out.printf("Person '%s' is identified for the face in: %s - %s, confidence: %f.%n",
                    person.name(), sourceImageFileName, identifyResult.faceId(), identifyResult.candidates().get(0).confidence());

            VerifyResult verifyResult = client.face().verifyFaceToPerson(identifyResult.faceId(), personId, personGroupId).get();
            System.out.printf("Verification result: is a match? %s. confidence: %f.%n", verifyResult.isIdentical(), verifyResult.confidence());
        }
        System.out.println();
    }
}
