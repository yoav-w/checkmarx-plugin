package com.checkmarx.jenkins;


import com.checkmarx.ws.CxJenkinsWebService.*;
import com.checkmarx.ws.CxJenkinsWebService.CxWSBasicRepsonse;
import com.checkmarx.ws.CxWSResolver.*;
import hudson.AbortException;
import hudson.util.IOUtils;
import org.apache.commons.codec.binary.Base64;
import org.apache.commons.codec.binary.Base64OutputStream;
import org.apache.log4j.Logger;

import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBException;
import javax.xml.bind.Marshaller;
import javax.xml.bind.Unmarshaller;
import javax.xml.namespace.QName;
import javax.xml.soap.MessageFactory;
import javax.xml.soap.MimeHeaders;
import javax.xml.soap.SOAPException;
import javax.xml.soap.SOAPMessage;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLConnection;
import java.util.Arrays;
import java.util.List;

/**
 * Created with IntelliJ IDEA.
 * User: denis
 * Date: 13/11/2013
 * Time: 18:12
 * Description:
 */
public class CxWebService {

    private final static Logger logger = Logger.getLogger(CxWebService.class);
    private final static QName CXWSRESOLVER_QNAME = new QName("http://Checkmarx.com", "CxWSResolver");
    private final static QName CXJENKINSWEBSERVICE_QNAME = new QName("http://Checkmarx.com/v7", "CxJenkinsWebService");
    private final static int WEBSERVICE_API_VERSION = 1;
    private final static String CXWSRESOLVER_PATH = "/cxwebinterface/cxwsresolver.asmx";
    private final static int LCID = 1033; // English

    private String sessionId;
    private CxJenkinsWebServiceSoap cxJenkinsWebServiceSoap;
    private final URL webServiceUrl;
    private String streamingSoapMessageTail;
    private HttpURLConnection streamingUrlConnection;

    public CxWebService(String serverUrl) throws MalformedURLException, AbortException
    {
        logger.info("Establishing connection with Checkmarx server at: " + serverUrl);
        URL serverUrlUrl = new URL(serverUrl);
        if (serverUrlUrl.getPath().length() > 0)
        {
            String message = "Checkmarx server url must not contain path: " + serverUrl;
            logger.debug(message);
            throw new AbortException(message);
        }
        URL resolverUrl = new URL(serverUrl + CXWSRESOLVER_PATH);

        logger.debug("Resolver url: " + resolverUrl);
        CxWSResolver cxWSResolver;
        try {
            cxWSResolver = new CxWSResolver(resolverUrl,CXWSRESOLVER_QNAME);  // TODO: Remove qname
        } catch (javax.xml.ws.WebServiceException e){
            logger.error("Failed to resolve Checkmarx webservice url with resolver at: " + resolverUrl);
            logger.error(e);
            throw new AbortException("Checkmarx server was not found on url: " + serverUrl);
        }
        CxWSResolverSoap cxWSResolverSoap =  cxWSResolver.getCxWSResolverSoap();
        CxWSResponseDiscovery cxWSResponseDiscovery = cxWSResolverSoap.getWebServiceUrl(CxClientType.JENKINS,WEBSERVICE_API_VERSION);
        if (!cxWSResponseDiscovery.isIsSuccesfull())
        {
            String message = "Failed to resolve Checkmarx webservice url: \n" + cxWSResponseDiscovery.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }

        webServiceUrl = new URL(cxWSResponseDiscovery.getServiceURL());
        logger.debug("Webservice url: " + webServiceUrl);
        CxJenkinsWebService cxJenkinsWebService = new CxJenkinsWebService(webServiceUrl,CXJENKINSWEBSERVICE_QNAME); // TODO: Remove qname
        cxJenkinsWebServiceSoap = cxJenkinsWebService.getCxJenkinsWebServiceSoap();

    }

    public void login(String username, String password) throws AbortException
    {
        sessionId=null;
        Credentials credentials = new Credentials();
        credentials.setUser(username);
        credentials.setPass(password);
        CxWSResponseLoginData cxWSResponseLoginData = cxJenkinsWebServiceSoap.login(credentials,LCID);

        if (!cxWSResponseLoginData.isIsSuccesfull())
        {
            logger.error("Login to Checkmarx server failed:");
            logger.error(cxWSResponseLoginData.getErrorMessage());
            throw new AbortException(cxWSResponseLoginData.getErrorMessage());
        }

        sessionId = cxWSResponseLoginData.getSessionId();
        logger.debug("Login successful, sessionId: " + sessionId);
    }

    public CxWSResponseRunID scan(CliScanArgs args) throws AbortException
    {
        assert sessionId!=null : "Trying to scan before login";

        CxWSResponseRunID cxWSResponseRunID = cxJenkinsWebServiceSoap.scan(sessionId,args);
        if (!cxWSResponseRunID.isIsSuccesfull())
        {
            String message = "Submission of sources for scan failed: \n" + cxWSResponseRunID.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }

        return cxWSResponseRunID;
    }


    private CxWSResponseScanStatus getScanStatus(CxWSResponseRunID cxWSResponseRunID) throws AbortException
    {
        assert sessionId!=null : "Trying to get scan status before login";
        CxWSResponseScanStatus cxWSResponseScanStatus = cxJenkinsWebServiceSoap.getStatusOfSingleScan(sessionId,cxWSResponseRunID.getRunId());
        if (!cxWSResponseScanStatus.isIsSuccesfull())
        {
            String message = "Error communicating with Checkmarx server: \n" + cxWSResponseScanStatus.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }
        return cxWSResponseScanStatus;
    }



    public long trackScanProgress(CxWSResponseRunID cxWSResponseRunID) throws AbortException
    {
        assert sessionId!=null : "Trying to track scan progress before login";

        boolean locReported = false;
        while (true)
        {
            CxWSResponseScanStatus status = this.getScanStatus(cxWSResponseRunID);
            switch (status.getCurrentStatus())
            {
                // In progress states
                case WAITING_TO_PROCESS:
                    logger.info("Scan job waiting for processing");
                    break ;

                case QUEUED:
                    if (!locReported)
                    {
                        logger.info("Source contains: " + status.getLOC() + " lines of code.");
                        locReported = true;
                    }
                    logger.info("Scan job queued at position: " + status.getQueuePosition());
                    break ;

                case UNZIPPING:
                    logger.info("Unzipping: " + status.getCurrentStagePercent() + "% finished");
                    logger.info("LOC: " + status.getLOC());
                    logger.info("StageMessage: " + status.getStageMessage());
                    logger.info("StepMessage: " + status.getStepMessage());
                    logger.info("StepDetails: " + status.getStepDetails());

                    break ;

                case WORKING:
                    logger.info("Scanning: " + status.getStageMessage() + " " + status.getStepDetails() +
                            " (stage: " + status.getCurrentStagePercent() + "%, total: "+ status.getTotalPercent() + "%)");
                    break ;


                // End of progress states
                case FINISHED:
                    logger.info("Scan Finished Successfully -  RunID: " + status.getRunId() + " ScanID:" + status.getScanId());
                    return status.getScanId();

                case FAILED:
                case DELETED:
                case UNKNOWN:
                case CANCELED:
                    String message = "Scan " + status.getStageName() + " -  RunID: " + status.getRunId() + " ScanID:" + status.getScanId();
                    logger.info(message);
                    logger.info("Stage Message" +  status.getStageMessage());
                    throw new AbortException(message);
            }

            try {
                Thread.sleep(10*1000);
            } catch (InterruptedException e)
            {
                String err = "Process interrupted while waiting for scan results";
                logger.error(err);
                logger.error(e);
                throw new AbortException(err);
            }
        }



    }

    public void retrieveScanReport(long scanId, File reportFile, CxWSReportType reportType) throws AbortException
    {
        assert sessionId!=null : "Trying to retrieve scan report before login";

        CxWSReportRequest cxWSReportRequest = new CxWSReportRequest();
        cxWSReportRequest.setScanID(scanId);
        cxWSReportRequest.setType(reportType);
        logger.info("Requesting " + reportType.toString().toUpperCase() + " Scan Report Generation");
        CxWSCreateReportResponse cxWSCreateReportResponse = cxJenkinsWebServiceSoap.createScanReport(sessionId,cxWSReportRequest);
        if (!cxWSCreateReportResponse.isIsSuccesfull())
        {
            String message = "Error requesting scan report generation: " + cxWSCreateReportResponse.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }



        // Wait for the report to become ready

        while (true)
        {
            CxWSReportStatusResponse cxWSReportStatusResponse = cxJenkinsWebServiceSoap.getScanReportStatus(sessionId,cxWSCreateReportResponse.getID());
            if (!cxWSReportStatusResponse.isIsSuccesfull())
            {
                String message = "Error retrieving scan report status: " + cxWSReportStatusResponse.getErrorMessage();
                logger.error(message);
                throw new AbortException(message);
            }
            if (cxWSReportStatusResponse.isIsFailed())
            {
                String message = "Failed to create scan report";
                logger.error("Web method getScanReportStatus returned status response with isFailed field set to true");
                logger.error(message);
                throw new AbortException(message);
            }

            if (cxWSReportStatusResponse.isIsReady())
            {
                logger.info("Scan report generated on Checkmarx server");
                break;
            }

            logger.info(reportType.toString().toUpperCase() + " Report generation in progress");
            try {
                Thread.sleep(5*1000);
            } catch (InterruptedException e)
            {
                String err = "Process interrupted while waiting for scan results";
                logger.error(err);
                logger.error(e);
                throw new AbortException(err);
            }
        }

        CxWSResponseScanResults cxWSResponseScanResults = cxJenkinsWebServiceSoap.getScanReport(sessionId,cxWSCreateReportResponse.getID());
        if (!cxWSResponseScanResults.isIsSuccesfull()) {
            String message = "Error retrieving scan report: " + cxWSResponseScanResults.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }

        // Save results on disk
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(reportFile);
            IOUtils.write(cxWSResponseScanResults.getScanResults(),fileOutputStream);
            fileOutputStream.close();

        } catch (IOException e)
        {
            logger.debug(e);
            String message = "Can't create report file: " + reportFile.getAbsolutePath();
            logger.info(message);
            throw new AbortException(message);
        }
        logger.info("Scan report written to: " + reportFile.getAbsolutePath());
    }

    public List<ProjectDisplayData> getProjectsDisplayData() throws AbortException
    {
        assert sessionId!=null : "Trying to retrieve projects display data before login";

        CxWSResponseProjectsDisplayData cxWSResponseProjectsDisplayData = this.cxJenkinsWebServiceSoap.getProjectsDisplayData(this.sessionId);
        if (!cxWSResponseProjectsDisplayData.isIsSuccesfull())
        {
            String message = "Error retrieving projects display data from server: "  + cxWSResponseProjectsDisplayData.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }

        return cxWSResponseProjectsDisplayData.getProjectList().getProjectDisplayData();
    }

    public List<Preset> getPresets() throws AbortException
    {
        assert sessionId!=null : "Trying to retrieve presetes before login";
        CxWSResponsePresetList cxWSResponsePresetList = this.cxJenkinsWebServiceSoap.getPresetList(this.sessionId);
        if (!cxWSResponsePresetList.isIsSuccesfull())
        {
            String message = "Error retrieving presets from server: "  + cxWSResponsePresetList.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }
        return cxWSResponsePresetList.getPresetList().getPreset();
    }

    // Source encoding is called "configuration" in server terms
    public List<ConfigurationSet> getSourceEncodings() throws AbortException
    {
        assert sessionId!=null : "Trying to retrieve configurations before login";
        CxWSResponseConfigSetList cxWSResponseConfigSetList = this.cxJenkinsWebServiceSoap.getConfigurationSetList(sessionId);
        if (!cxWSResponseConfigSetList.isIsSuccesfull())
        {
            String message = "Error retrieving configurations from server: "  + cxWSResponseConfigSetList.getErrorMessage();
            logger.error(message);
            throw new AbortException(message);
        }
        return cxWSResponseConfigSetList.getConfigSetList().getConfigurationSet();
    }

    public CxWSBasicRepsonse validateProjectName(String cxProjectName)
    {
        assert sessionId!=null : "Trying to validate project name before login";
        return this.cxJenkinsWebServiceSoap.isValidProjectName(sessionId,cxProjectName,""); // TODO: Specify group id
    }

    /**
     * Same as "scan" method, but works by streaming the LocalCodeContainer.zippedFile contents.
     * The attribute LocalCodeContainer.zippedFile inside args is ignored, and zippedFileInputStream is used instead.
     * @param args
     * @param zippedFileInputStream - Input stream to used instead of LocalCodeContainer.zippedFile attribute
     * @return
     * @throws AbortException
     */

    public CxWSResponseRunID scanSreaming(CliScanArgs args, File base64ZipFile)
    {
        try {

            final String soapMessageHead = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n" +
                    "<soap:Envelope xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\" xmlns:xsd=\"http://www.w3.org/2001/XMLSchema\" xmlns:soap=\"http://schemas.xmlsoap.org/soap/envelope/\">\n" +
                    "  <soap:Body>\n" +
                    "    <Scan xmlns=\"http://Checkmarx.com/v7\">\n" +
                    "      <sessionId>" + sessionId + "</sessionId>\n" +
                    "      <args>\n" +
                    "        <PrjSettings>\n" +
                    "          <projectID>" + args.getPrjSettings().getProjectID()  + "</projectID>\n" +
                    "          <ProjectName>" + args.getPrjSettings().getProjectName() + "</ProjectName>\n" +
                    "          <PresetID>" + args.getPrjSettings().getPresetID() + "</PresetID>\n" +
                    "          <AssociatedGroupID>" + args.getPrjSettings().getAssociatedGroupID() + "</AssociatedGroupID>\n" +
                    "          <ScanConfigurationID>" + args.getPrjSettings().getScanConfigurationID() + "</ScanConfigurationID>\n" +
                    "          <Description></Description>\n" +
                    "        </PrjSettings>\n" +
                    "        <SrcCodeSettings>\n" +
                    "          <SourceOrigin>" + args.getSrcCodeSettings().getSourceOrigin().value() + "</SourceOrigin>\n" +
                    "          <UserCredentials>\n" +
                    "            <User></User>\n" +
                    "            <Pass></Pass>\n" +
                    "          </UserCredentials>\n" +
                    "          <PathList>\n" +
                    "            <ScanPath xsi:nil=\"true\" />\n" +
                    "            <ScanPath xsi:nil=\"true\" />\n" +
                    "          </PathList>\n" +
                    "          <PackagedCode>\n" +
                    "            <ZippedFile>";

            streamingSoapMessageTail = "</ZippedFile>\n" +
                    "            <FileName>src.zip</FileName>\n" +
                    "          </PackagedCode>\n" +
                    "          <SourcePullingAction>" + args.getSrcCodeSettings().getSourcePullingAction() + "</SourcePullingAction>\n" +
                    "        </SrcCodeSettings>\n" +
                    "        <IsPrivateScan>false</IsPrivateScan>\n" +
                    "        <IsIncremental>false</IsIncremental>\n" +
                    "      </args>\n" +
                    "    </Scan>\n" +
                    "  </soap:Body>\n" +
                    "</soap:Envelope>";

            final JAXBContext context = JAXBContext.newInstance(Scan.class,ScanResponse.class);//"com.checkmarx.ws.CxJenkinsWebService");
            final Marshaller marshaller = context.createMarshaller();

            Scan scan = new Scan();
            scan.setArgs(args);
            scan.setSessionId(sessionId);

            ByteArrayOutputStream scanMessage = new ByteArrayOutputStream();
            marshaller.marshal(scan,scanMessage);
            ByteArrayInputStream scanMessgaeInStream = new ByteArrayInputStream(scanMessage.toByteArray());

            MessageFactory mf = MessageFactory.newInstance();
            final SOAPMessage message = mf.createMessage();

            message.writeTo(System.out);



            /*
            POST /cxwebinterface/Jenkins/CxJenkinsWebService.asmx HTTP/1.1
            Host: localhost
            Content-Type: text/xml; charset=utf-8
            Content-Length: length
            SOAPAction: "http://Checkmarx.com/v7/Scan"
            */


            streamingUrlConnection = (HttpURLConnection)webServiceUrl.openConnection();
            streamingUrlConnection.setRequestMethod("POST");
            streamingUrlConnection.addRequestProperty("Content-Type","text/xml; charset=utf-8");
            streamingUrlConnection.addRequestProperty("SOAPAction","\"http://Checkmarx.com/v7/Scan\"");
            streamingUrlConnection.setDoOutput(true);

            final long length = soapMessageHead.getBytes("UTF-8").length + streamingSoapMessageTail.getBytes("UTF-8").length + base64ZipFile.length();
            streamingUrlConnection.setFixedLengthStreamingMode((int)length);

            streamingUrlConnection.connect();
            final OutputStream os = streamingUrlConnection.getOutputStream();

            os.write(soapMessageHead.getBytes("UTF-8"));
            final FileInputStream fis = new FileInputStream(base64ZipFile);

            org.apache.commons.io.IOUtils.copyLarge(fis, os);

            os.write(streamingSoapMessageTail.getBytes("UTF-8"));
            os.close();


            int responseCode = streamingUrlConnection.getResponseCode();
            System.out.println("Response code: " + responseCode);
            //byte[] buffer = new byte[64*1024];
            //final int readBytes = streamingUrlConnection.getInputStream().read(buffer);
            //byte[] filledBuffer = Arrays.copyOf(buffer,readBytes);
            //String output = new String(filledBuffer, "UTF-8");
            //System.out.println("Connection output: \n" + output);

            /*
            HTTP/1.1 200 OK
            Content-Type: text/xml; charset=utf-8
            Content-Length: length

            <?xml version="1.0" encoding="utf-8"?>
            <soap:Envelope xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance" xmlns:xsd="http://www.w3.org/2001/XMLSchema" xmlns:soap="http://schemas.xmlsoap.org/soap/envelope/">
            <soap:Body>
            <ScanResponse xmlns="http://Checkmarx.com/v7">
            <ScanResult>
            <ProjectID>long</ProjectID>
            <RunId>string</RunId>
            </ScanResult>
            </ScanResponse>
            </soap:Body>
            </soap:Envelope> */

            final InputStream resultStream = streamingUrlConnection.getInputStream();
            XMLInputFactory xif = XMLInputFactory.newFactory();
            XMLStreamReader xsr = xif.createXMLStreamReader(resultStream);
            xsr.nextTag();
            while(!xsr.getLocalName().equals("ScanResponse")) {
                xsr.nextTag();
            }

            final Unmarshaller unmarshaller = context.createUnmarshaller();
            final ScanResponse scanResponse = (ScanResponse)unmarshaller.unmarshal(xsr);
            xsr.close();

            //CxWSResponseRunID result = new CxWSResponseRunID();
            //result.setProjectID(5);
            //result.setRunId("3");
            return scanResponse.getScanResult();


        } catch (IOException e) {
            e.printStackTrace();
        } catch (JAXBException e) {
            e.printStackTrace();
        } catch (SOAPException e)
        {
            e.printStackTrace();
        } catch (XMLStreamException e) {
            e.printStackTrace();
        }
        return null;
    }


    public boolean isLoggedIn()
    {
        return this.sessionId!=null;
    }

}
