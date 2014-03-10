package com.rackspace.papi.components.clientauth.atomfeed.sax;

// Retrieves admin tokens from os auth for the atom feed reader
// TODO: this should be refactored into the openstack stuff I think
// TODO: also I wish JSON instead of sax and xml and marshalling :(
import com.rackspace.auth.ResponseUnmarshaller;
import com.rackspace.auth.openstack.AdminToken;
import com.rackspace.papi.commons.util.http.HttpStatusCode;
import com.rackspace.papi.commons.util.http.ServiceClient;
import com.rackspace.papi.commons.util.http.ServiceClientResponse;
import com.rackspace.papi.commons.util.transform.jaxb.JaxbEntityToXml;
import com.rackspace.papi.service.serviceclient.akka.AkkaServiceClient;
import org.openstack.docs.identity.api.v2.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.ws.rs.core.MediaType;
import javax.xml.bind.JAXBContext;
import javax.xml.bind.JAXBElement;
import javax.xml.bind.JAXBException;
import java.util.HashMap;

public class AdminTokenProvider {

   private String authUrl;
    private AkkaServiceClient client;
   private JAXBContext coreJaxbContext;
   private ResponseUnmarshaller marshaller;
   private AdminToken curAdminToken;
   private final String requestBody;
   private static final org.slf4j.Logger LOG = LoggerFactory.getLogger(AdminTokenProvider.class);

   public AdminTokenProvider(AkkaServiceClient client, String authUrl, String username, String password) {
      this.client = client;
      this.authUrl = authUrl;
       setJAXBContext();
      marshaller = new ResponseUnmarshaller(coreJaxbContext);
      ObjectFactory factory = new ObjectFactory();

      PasswordCredentialsRequiredUsername credentials = new PasswordCredentialsRequiredUsername();
      credentials.setUsername(username);
      credentials.setPassword(password);

      JAXBElement jaxbCredentials = factory.createPasswordCredentials(credentials);

      AuthenticationRequest request = new AuthenticationRequest();
      request.setCredential(jaxbCredentials);

      JaxbEntityToXml jaxbToString = new JaxbEntityToXml(coreJaxbContext);
      requestBody = jaxbToString.transform(factory.createAuth(request));
   }

   private void setJAXBContext() {
      try {
         coreJaxbContext = JAXBContext.newInstance(
                 org.openstack.docs.identity.api.v2.ObjectFactory.class,
                 com.rackspace.docs.identity.api.ext.rax_auth.v1.ObjectFactory.class);
      } catch (JAXBException ex) {
          LOG.trace("Unable to set JAXB Context", ex);
      }
   }
   
   public String getFreshAdminToken(){
      
      curAdminToken = null;
      return getAdminToken();
   }

   public String getAdminToken() {

      String adminToken = curAdminToken != null && curAdminToken.isValid() ? curAdminToken.getToken() : null;

      if (adminToken == null) {
          final ServiceClientResponse serviceResponse = client.post(AkkaServiceClient.ADMIN_TOKEN_KEY,
                  authUrl + "/tokens",
                  new HashMap<String, String>(),
                  requestBody,
                  MediaType.APPLICATION_XML_TYPE );

         switch (HttpStatusCode.fromInt(serviceResponse.getStatusCode())) {
            case OK:
               final AuthenticateResponse authenticateResponse = marshaller.unmarshall(serviceResponse.getData(), AuthenticateResponse.class);

               Token token = authenticateResponse.getToken();
               curAdminToken = new AdminToken(token.getId(), token.getExpires().toGregorianCalendar());
               adminToken = curAdminToken.getToken();
               break;

            default:
               LOG.error("Unable to get admin token.  Verify admin credentials. " + serviceResponse.getStatusCode());
               curAdminToken = null;
               break;
         }
      }

      return adminToken;
   }
}
