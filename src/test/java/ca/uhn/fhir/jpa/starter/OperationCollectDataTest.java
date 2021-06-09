package ca.uhn.fhir.jpa.starter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.IllegalArgumentException;
import java.nio.charset.StandardCharsets;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.jpa.starter.util.OpenmrsAuthInterceptor;
import ca.uhn.fhir.parser.IParser;
import ca.uhn.fhir.parser.StrictErrorHandler;
import ca.uhn.fhir.rest.api.Constants;
import ca.uhn.fhir.rest.client.api.IGenericClient;
import ca.uhn.fhir.rest.client.api.ServerValidationModeEnum;
import ca.uhn.fhir.rest.client.interceptor.LoggingInterceptor;
import ca.uhn.fhir.rest.server.exceptions.InvalidRequestException;
import com.google.common.base.Charsets;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.io.FilenameUtils;
import org.apache.commons.io.IOUtils;
import org.apache.http.Header;
import org.apache.http.HttpEntity;
import org.apache.http.HttpHeaders;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.util.EntityUtils;
import org.hl7.fhir.r4.model.Measure;
import org.hl7.fhir.r4.model.MeasureReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Parameters;
import org.hl7.fhir.r4.model.Patient;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.web.server.LocalServerPort;
import org.springframework.http.MediaType;
import org.springframework.test.context.junit.jupiter.SpringExtension;

@ExtendWith(SpringExtension.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, classes = Application.class, properties = {
		"spring.batch.job.enabled=false",
		"spring.datasource.url=jdbc:h2:mem:dbr4",
		"hapi.fhir.fhir_version=r4",
		"hapi.fhir.subscription.websocket_enabled=false",
		"hapi.fhir.empi_enabled=false",
		"hapi.fhir.auto_create_placeholder_reference_targets=true",
		// Override is currently required when using Empi as the construction of the
		// Empi beans are ambiguous as they are constructed multiple places. This is
		// evident when running in a spring boot environment
		"spring.main.allow-bean-definition-overriding=true" 
	})
public class OperationCollectDataTest {

	private static final String OBS_FILE_PATH = "ObsBundle.json";

	private static final String OBS_FILE_PATH2 = "ObsBundle2.json";

	private static final String OBS_FILE_XML_PATH2 = "ObsBundle2.xml";

	private static final String MEASURE_FILE_PATH = "FhirMeasure.json";

	private static final String MEASURE2_FILE_PATH = "FhirMeasure2.json";

	private static final String MEASURE2_XML_FILE_PATH = "FhirMeasure2.xml";

	private static String OBS_FILE_XML_PATH = "ObsBundle.xml";

	private static String MEASURE_XML_FILE_PATH = "FhirMeasure.xml";

	public static final MediaType FHIR_JSON_MEDIA_TYPE = MediaType.valueOf("application/fhir+json");

	public static final MediaType FHIR_XML_MEDIA_TYPE = MediaType.valueOf("application/fhir+xml");

	private static final String MEASURE_RESOURCE_ID = "TX-PVLS";

	private static final String MEASURE_RESOURCE2_ID = "TX-CURR";

	private static final String UNDER_SCORE_MEASURE_RESOURCE_ID = "TX_PVLS";

	private static final String WRONG_MEASURE_RESOURCE_ID = "TX-JDFG";

	private static final String PARAM_NAME1 = "measureReport";

	private static final String PARAM_NAME2 = "resource";

	@Value("${openmrs.login.username}")
	private String username;

	@Value("${openmrs.login.password}")
	private String password;

	protected CloseableHttpClient ourHttpClient;

	protected String ourServerBase;

	private IGenericClient ourClient;

	private FhirContext ourCtx;

	@LocalServerPort
	private int port;

	@Autowired
	private OpenmrsAuthInterceptor authInterceptor;

	@Test
	public void testCollectDataOperationOnJsonData() throws IOException {
		// Post the Measure Resource
		Measure measure = readMeasureFromJsonFile();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE_ID).encodedJson().execute();

		// post the obs bundle
		postResource(ourServerBase, OBS_FILE_PATH);

		// fetch parameter result from the operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE_ID
				+ "/$collect-data?periodStart=2021-01-01&periodEnd=2021-01-31");

		assertTrue(result.hasParameter(PARAM_NAME1));
		assertTrue(result.hasParameter(PARAM_NAME2));
		assertEquals(5, result.getParameter().size());

		assertTrue(result.getParameter().get(0).getResource() instanceof MeasureReport);
		assertTrue(result.getParameter().get(1).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(2).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(3).getResource() instanceof Patient);
		assertTrue(result.getParameter().get(4).getResource() instanceof Patient);

		// get measure report from the Parameter Result
		MeasureReport report = (MeasureReport) result.getParameter().get(0).getResource();
		assertEquals(report.getEvaluatedResource().size(), 4);
		assertEquals(report.getMeasure(), "Measure/TX_PVLS");
		assertEquals(report.getStatus(), MeasureReport.MeasureReportStatus.COMPLETE);
		assertEquals(report.getType(), MeasureReport.MeasureReportType.DATACOLLECTION);

		// get Observation Bundle from the Parameter Result
		Observation observation1 = (Observation) result.getParameter().get(1).getResource();
		assertEquals(observation1.getCode().getCodingFirstRep().getCode(), "1305AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

		Observation observation2 = (Observation) result.getParameter().get(2).getResource();
		assertEquals(observation2.getCode().getCodingFirstRep().getCode(), "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	}

	@Test
	public void testCollectDataOperationOnJsonDataForTX_CURR() throws IOException {
		// Post the Measure Resource
		Measure measure = readMeasureFromJsonFileTX_CURR();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE2_ID).encodedJson().execute();

		// post the obs bundle
		postResource(ourServerBase, OBS_FILE_PATH2);

		// fetch parameter result from the operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE2_ID
				+ "/$collect-data?periodStart=2021-05-28&periodEnd=2021-06-01");

		assertTrue(result.hasParameter(PARAM_NAME1));
		assertTrue(result.hasParameter(PARAM_NAME2));
		assertEquals(5, result.getParameter().size());

		assertTrue(result.getParameter().get(0).getResource() instanceof MeasureReport);
		assertTrue(result.getParameter().get(1).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(2).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(3).getResource() instanceof Patient);
		assertTrue(result.getParameter().get(4).getResource() instanceof Patient);

		// get measure report from the Parameter Result
		MeasureReport report = (MeasureReport) result.getParameter().get(0).getResource();
		assertEquals(report.getEvaluatedResource().size(), 4);
		assertEquals(report.getMeasure(), "Measure/TX_CURR");
		assertEquals(report.getStatus(), MeasureReport.MeasureReportStatus.COMPLETE);
		assertEquals(report.getType(), MeasureReport.MeasureReportType.DATACOLLECTION);

		// get Observation Bundle from the Parameter Result
		Observation observation1 = (Observation) result.getParameter().get(1).getResource();
		assertEquals(observation1.getCode().getCodingFirstRep().getCode(), "160119AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

		Observation observation2 = (Observation) result.getParameter().get(2).getResource();
		assertEquals(observation2.getCode().getCodingFirstRep().getCode(), "160119AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	}

	@Test
	public void testCollectDataOperationOnXmlDataForTX_CURR() throws IOException {
		// Post the Measure Resource
		Measure measure = readMeasureFromXmlFileTX_CURR();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE2_ID).encodedXml().execute();

		// post the obs bundle
		postResource(ourServerBase, OBS_FILE_XML_PATH2);

		// fetch parameter result from the operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE2_ID
				+ "/$collect-data?periodStart=2021-05-28&periodEnd=2021-06-01");

		assertTrue(result.hasParameter(PARAM_NAME1));
		assertTrue(result.hasParameter(PARAM_NAME2));
		assertEquals(5, result.getParameter().size());

		assertTrue(result.getParameter().get(0).getResource() instanceof MeasureReport);
		assertTrue(result.getParameter().get(1).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(2).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(3).getResource() instanceof Patient);
		assertTrue(result.getParameter().get(4).getResource() instanceof Patient);

		// get measure report from the Parameter Result
		MeasureReport report = (MeasureReport) result.getParameter().get(0).getResource();
		assertEquals(report.getEvaluatedResource().size(), 4);
		assertEquals(report.getMeasure(), "Measure/TX_CURR");
		assertEquals(report.getStatus(), MeasureReport.MeasureReportStatus.COMPLETE);
		assertEquals(report.getType(), MeasureReport.MeasureReportType.DATACOLLECTION);

		// get Observation Bundle from the Parameter Result
		Observation observation1 = (Observation) result.getParameter().get(1).getResource();
		assertEquals(observation1.getCode().getCodingFirstRep().getCode(), "160119AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

		Observation observation2 = (Observation) result.getParameter().get(2).getResource();
		assertEquals(observation2.getCode().getCodingFirstRep().getCode(), "160119AAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	}

	@Test
	public void testCollectDataOperationOnXmlData() throws IOException {

		// Post the Measure Resource
		Measure measure = readMeasureFromXmlFile();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE_ID).encodedXml().execute();
		// post the obs Bundle
		postResource(ourServerBase, OBS_FILE_XML_PATH);
		// fetch parameter result from the Operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE_ID
				+ "/$collect-data?periodStart=2021-01-01&periodEnd=2021-01-31");

		assertTrue(result.hasParameter(PARAM_NAME1));
		assertTrue(result.hasParameter(PARAM_NAME2));
		assertEquals(5, result.getParameter().size());

		assertTrue(result.getParameter().get(0).getResource() instanceof MeasureReport);
		assertTrue(result.getParameter().get(1).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(2).getResource() instanceof Observation);
		assertTrue(result.getParameter().get(3).getResource() instanceof Patient);
		assertTrue(result.getParameter().get(4).getResource() instanceof Patient);
		// get measure report from the Parameter Result
		MeasureReport report = (MeasureReport) result.getParameter().get(0).getResource();
		assertEquals(report.getEvaluatedResource().size(), 4);
		assertEquals(report.getMeasure(), "Measure/TX_PVLS");
		assertEquals(report.getStatus(), MeasureReport.MeasureReportStatus.COMPLETE);
		assertEquals(report.getType(), MeasureReport.MeasureReportType.DATACOLLECTION);
		// get Observation Bundle from the Parameter Result
		Observation observation1 = (Observation) result.getParameter().get(1).getResource();
		assertEquals(observation1.getCode().getCodingFirstRep().getCode(), "1305AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");

		Observation observation2 = (Observation) result.getParameter().get(2).getResource();
		assertEquals(observation2.getCode().getCodingFirstRep().getCode(), "856AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");
	}

	@Test
	public void shouldReturnNoResultsIfThereNoObsRecordedInTheStatedDatePeriodsOnJson() throws IOException {

		// Post the Measure Resource
		Measure measure = readMeasureFromJsonFile();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE_ID).encodedJson().execute();
		// post the obs bundle
		postResource(ourServerBase, OBS_FILE_PATH);
		// fetch parameter result from the operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE_ID
				+ "/$collect-data?periodStart=2020-01-01&periodEnd=2020-01-31");

		assertTrue(result.hasParameter(PARAM_NAME1));

		// no obs resources
		assertFalse(result.hasParameter(PARAM_NAME2));
		// returns only the empty measure report
		assertEquals(1, result.getParameter().size());
	}

	@Test
	public void shouldReturnNoResultsIfThereNoObsRecordedInTheStatedDatePeriodsOnXml() throws IOException {

		// Post the Measure Resource
		Measure measure = readMeasureFromXmlFile();
		ourClient.update().resource(measure).withId(MEASURE_RESOURCE_ID).encodedXml().execute();
		// post the obs bundle
		postResource(ourServerBase, OBS_FILE_XML_PATH);
		// fetch parameter result from the operation
		Parameters result = fetchParameter(ourServerBase + "/Measure/" + MEASURE_RESOURCE_ID
				+ "/$collect-data?periodStart=2020-01-01&periodEnd=2020-01-31");

		assertTrue(result.hasParameter(PARAM_NAME1));

		// no obs resources
		assertFalse(result.hasParameter(PARAM_NAME2));
		// returns only the empty measure report
		assertEquals(1, result.getParameter().size());
	}

	@Test
	public void shouldFailIfMeasureResourceIdIsWrongAsJson() throws IOException {

		// Post the Measure Resource
		Measure measure = readMeasureFromJsonFile();
		try {
			ourClient.update().resource(measure).withId(WRONG_MEASURE_RESOURCE_ID).encodedJson().execute();
		} catch (Exception exception) {
			assertNotNull(exception);
			assertNotNull(exception.getStackTrace());
			assertTrue(exception.getMessage().contains("Illegal character in path at index 44:"));
			assertEquals(exception.getClass(), IllegalArgumentException.class);
		}
	}

	@Test
	public void shouldFailIfMeasureResourceIdIsWrongAsXml() throws IOException {

		// Post the Measure Resource
		Measure measure = readMeasureFromXmlFile();
		try {
			ourClient.update().resource(measure).withId(WRONG_MEASURE_RESOURCE_ID).encodedXml().execute();
		} catch (Exception exception) {
			assertNotNull(exception);
			assertNotNull(exception.getStackTrace());
			assertTrue(exception.getMessage().contains("Illegal character in path at index 44:"));
			assertEquals(exception.getClass(), IllegalArgumentException.class);
		}
	}

	@Test
	public void shouldReturnErrorWhenUnderScoreIsUsedInMeasureIdAsJson() throws IOException {
		// Post the Measure Resource
		Measure measure = readMeasureFromJsonFile();
		Exception exception = Assertions.assertThrows(InvalidRequestException.class,
				() -> ourClient.update().resource(measure).withId(UNDER_SCORE_MEASURE_RESOURCE_ID).encodedJson()
						.execute(),
				"HTTP 400 Bad Request: Can not process entity with ID[TX_PVLS], this is not a valid FHIR ID");
		assertNotNull(exception);
		assertNotNull(exception.getMessage());
		assertTrue(exception.getMessage().contains(
				"HTTP 400 Bad Request: Can not process entity with ID[TX_PVLS], this is not a valid FHIR ID"));
	}

	@Test
	public void shouldReturnErrorWhenUnderScoreIsUsedInMeasureIdAsXml() throws IOException {
		// Post the Measure Resource
		Measure measure = readMeasureFromXmlFile();
		Exception exception = Assertions.assertThrows(InvalidRequestException.class,
				() -> ourClient.update().resource(measure).withId(UNDER_SCORE_MEASURE_RESOURCE_ID).encodedJson()
						.execute(),
				"HTTP 400 Bad Request: Can not process entity with ID[TX_PVLS], this is not a valid FHIR ID");
		assertNotNull(exception);
		assertNotNull(exception.getMessage());
		assertTrue(exception.getMessage().contains(
				"HTTP 400 Bad Request: Can not process entity with ID[TX_PVLS], this is not a valid FHIR ID"));
	}

	@BeforeEach
	void beforeEach() {
		ourServerBase = "http://localhost:" + port + "/fhir";
		ourCtx = FhirContext.forR4();
		ourCtx.getRestfulClientFactory().setServerValidationMode(ServerValidationModeEnum.NEVER);
		ourCtx.getRestfulClientFactory().setSocketTimeout(1200 * 1000);
		ourCtx.setParserErrorHandler(new StrictErrorHandler());

		ourHttpClient = HttpClientBuilder.create().build();
		setHapiClient();
	}

	private Parameters fetchParameter(String theUrl) throws IOException {
		HttpGet get = new HttpGet(theUrl);

		String auth = username + ":" + password;
		byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.UTF_8));
		String authHeader = "Basic " + new String(encodedAuth);
		get.addHeader(HttpHeaders.AUTHORIZATION, authHeader);

		try (CloseableHttpResponse resp = ourHttpClient.execute(get)) {
			IParser parser;
			HttpEntity httpEntity = resp.getEntity();
			Header contentType = httpEntity.getContentType();
			String mimeType = contentType.getValue();

			MediaType responseMediaType = MediaType.parseMediaType(mimeType);
			if (FHIR_JSON_MEDIA_TYPE.isCompatibleWith(responseMediaType)
					|| MediaType.APPLICATION_JSON.isCompatibleWith(responseMediaType)) {
				parser = ourCtx.newJsonParser();
			} else if (FHIR_XML_MEDIA_TYPE.isCompatibleWith(responseMediaType)
					|| MediaType.APPLICATION_XML.isCompatibleWith(responseMediaType)
					|| MediaType.TEXT_XML.isCompatibleWith(responseMediaType)) {
				parser = ourCtx.newXmlParser();
			} else {
				throw new AssertionError("Cannot handle response of type " + responseMediaType.toString());
			}

			return parser.parseResource(Parameters.class, EntityUtils.toString(resp.getEntity(), Charsets.UTF_8));
		}

	}

	private void postResource(String theUrl, String filePath) throws IOException {
		HttpPost post = new HttpPost(theUrl);

		if (FilenameUtils.getExtension(filePath).equals("json")) {
			String json = readFile(filePath);
			StringEntity entity = new StringEntity(json, StandardCharsets.UTF_8);

			post.setEntity(entity);
			post.setHeader(HttpHeaders.ACCEPT, FHIR_JSON_MEDIA_TYPE.toString());
			post.setHeader(HttpHeaders.CONTENT_TYPE, FHIR_JSON_MEDIA_TYPE.toString());
		} else if (FilenameUtils.getExtension(filePath).equals("xml")) {
			String xml = readFile(filePath);
			StringEntity entity = new StringEntity(xml, StandardCharsets.UTF_8);

			post.setEntity(entity);
			post.setHeader(HttpHeaders.ACCEPT, FHIR_XML_MEDIA_TYPE.toString());
			post.setHeader(HttpHeaders.CONTENT_TYPE, FHIR_XML_MEDIA_TYPE.toString());
		} else {
			throw new AssertionError("Cannot handle post content type ");
		}

		String auth = username + ":" + password;
		byte[] encodedAuth = Base64.encodeBase64(auth.getBytes(StandardCharsets.UTF_8));
		String authHeader = "Basic " + new String(encodedAuth);
		post.addHeader(HttpHeaders.AUTHORIZATION, authHeader);

		post.addHeader(Constants.HEADER_CACHE_CONTROL, Constants.CACHE_CONTROL_NO_CACHE);

		try (CloseableHttpResponse res = ourHttpClient.execute(post)) {
			if (res.getStatusLine().getStatusCode() != 200) {
				throw new IllegalStateException("Attempting to POST resource bundle failed with status code "
						+ res.getStatusLine().getStatusCode() + " "
						+ EntityUtils.toString(res.getEntity(), StandardCharsets.UTF_8));
			}
		}
	}

	private String readFile(String path) throws IOException {
		return IOUtils.toString(getClass().getClassLoader().getResourceAsStream(path), StandardCharsets.UTF_8);
	}

	private void setHapiClient() {
		ourClient = ourCtx.newRestfulGenericClient(ourServerBase);
		ourClient.registerInterceptor(new LoggingInterceptor(true));
		ourClient.registerInterceptor(authInterceptor);
	}

	public Measure readMeasureFromJsonFile() throws IOException {
		String json = readFile(MEASURE_FILE_PATH);

		IParser parser = ourCtx.newJsonParser();
		return parser.parseResource(Measure.class, json);
	}

	public Measure readMeasureFromXmlFile() throws IOException {
		String xml = readFile(MEASURE_XML_FILE_PATH);

		IParser parser = ourCtx.newXmlParser();
		return parser.parseResource(Measure.class, xml);
	}

	public Measure readMeasureFromJsonFileTX_CURR() throws IOException {
		String json = readFile(MEASURE2_FILE_PATH);

		IParser parser = ourCtx.newJsonParser();
		return parser.parseResource(Measure.class, json);
	}

	public Measure readMeasureFromXmlFileTX_CURR() throws IOException {
		String xml = readFile(MEASURE2_XML_FILE_PATH);

		IParser parser = ourCtx.newXmlParser();
		return parser.parseResource(Measure.class, xml);
	}
}
