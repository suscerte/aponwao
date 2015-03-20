/*
 * @(#)OCSPChecker.java	1.8 08/09/08
 *
 * Copyright 2006 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package flex.aponwao.core.validator;

import java.io.*;
import java.math.BigInteger;
import java.util.*;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.security.Security;
import java.security.cert.*;
import java.net.*;
import javax.security.auth.x500.X500Principal;
import sun.security.util.*;
import sun.security.x509.*;

/**
 * OCSPChecker is a <code>PKIXCertPathChecker</code> that uses the 
 * Online Certificate Status Protocol (OCSP) as specified in RFC 2560
 * <a href="http://www.ietf.org/rfc/rfc2560.txt">
 * http://www.ietf.org/rfc/rfc2560.txt</a>.
 *
 * @version 	1.8 09/08/08
 * @author	Ram Marti	
 */
class OCSPChecker extends PKIXCertPathChecker {
 
    public static final String OCSP_ENABLE_PROP = "ocsp.enable";
    public static final String OCSP_URL_PROP = "ocsp.responderURL";
    public static final String OCSP_CERT_SUBJECT_PROP =
	"ocsp.responderCertSubjectName";
    public static final String OCSP_CERT_ISSUER_PROP =
	"ocsp.responderCertIssuerName";
    public static final String OCSP_CERT_NUMBER_PROP =
	"ocsp.responderCertSerialNumber";

    private static final String HEX_DIGITS = "0123456789ABCDEFabcdef";
    private static final Debug DEBUG = Debug.getInstance("certpath");
    private static final boolean dump = false; 

    // Supported extensions
    private static final int OCSP_NONCE_DATA[] = 
	{ 1, 3, 6, 1, 5, 5, 7, 48, 1, 2 };
    private static final ObjectIdentifier OCSP_NONCE_OID;
    static {
        OCSP_NONCE_OID = ObjectIdentifier.newInternal(OCSP_NONCE_DATA);
    }

    private int remainingCerts;

    private X509Certificate[] certs;

    private CertPath cp;

    private PKIXParameters pkixParams;

    private boolean onlyEECert = false;

    /**
     * Default Constructor 
     *
     * @param certPath the X509 certification path
     * @param pkixParams the input PKIX parameter set
     * @exception CertPathValidatorException Exception thrown if cert path
     * does not validate.
     */
    OCSPChecker(CertPath certPath, PKIXParameters pkixParams) 
        throws CertPathValidatorException {
        this(certPath, pkixParams, false);
    }
  
    OCSPChecker(CertPath certPath, PKIXParameters pkixParams, boolean onlyEECert) 
        throws CertPathValidatorException {

	this.cp = certPath; 
	this.pkixParams = pkixParams;
        this.onlyEECert = onlyEECert;
	List tmp = cp.getCertificates();
	certs =
	    (X509Certificate[]) tmp.toArray(new X509Certificate[tmp.size()]);
	init(false);
    }
    
    /**
     * Initializes the internal state of the checker from parameters
     * specified in the constructor
     */
    public void init(boolean forward) throws CertPathValidatorException {
	if (!forward) {
	    remainingCerts = certs.length + 1;
	} else {
	    throw new CertPathValidatorException(
		"Forward checking not supported");
	}
    }

    public boolean isForwardCheckingSupported() {
	return false;
    }

    public Set<String> getSupportedExtensions() {
	return Collections.<String>emptySet();
    }

    /**
     * Sends an OCSPRequest for the certificate to the OCSP Server and
     * processes the response back from the OCSP Server.
     *
     * @param cert the Certificate
     * @param unresolvedCritExts the unresolved critical extensions
     * @exception CertPathValidatorException Exception is thrown if the 
     *            certificate has been revoked.
     */
    public void check(Certificate cert, Collection<String> unresolvedCritExts)
	throws CertPathValidatorException {

	// Decrement the certificate counter
	remainingCerts--;

	try {
	    X509Certificate responderCert = null;
	    boolean seekResponderCert = false;
	    X500Principal responderSubjectName = null;
	    X500Principal responderIssuerName = null;
	    BigInteger responderSerialNumber = null;

	    boolean seekIssuerCert = true;
	    X509CertImpl issuerCertImpl = null;
	    X509CertImpl currCertImpl =
		X509CertImpl.toImpl((X509Certificate)cert);
            if (onlyEECert && currCertImpl.getBasicConstraints() != -1) {
                if (DEBUG != null) {
                    DEBUG.println("Skipping revocation check, not end entity cert");
                }
                return;
            }

	    /*
	     * OCSP security property values, in the following order:
	     *   1. ocsp.responderURL
	     *   2. ocsp.responderCertSubjectName
	     *   3. ocsp.responderCertIssuerName
	     *   4. ocsp.responderCertSerialNumber
	     */
	    String[] properties = getOCSPProperties();

	    // Check whether OCSP is feasible before seeking cert infomation
	    URL url = getOCSPServerURL(currCertImpl, properties);

	    // When responder's subject name is set then the issuer/serial 
	    // properties are ignored
	    if (properties[1] != null) {
		responderSubjectName = new X500Principal(properties[1]);

	    } else if (properties[2] != null && properties[3] != null) {
	        responderIssuerName = new X500Principal(properties[2]);
		// remove colon or space separators
	        String value = stripOutSeparators(properties[3]);
		responderSerialNumber = new BigInteger(value, 16);

	    } else if (properties[2] != null || properties[3] != null) {
		throw new CertPathValidatorException(
		    "Must specify both ocsp.responderCertIssuerName and " +
		    "ocsp.responderCertSerialNumber properties");
	    }

	    // If the OCSP responder cert properties are set then the 
	    // identified cert must be located in the trust anchors or
	    // in the cert stores.
	    if (responderSubjectName != null || responderIssuerName != null) {
		seekResponderCert = true;
	    }

	    // Set the issuer certificate to the next cert in the chain
	    // (unless we're processing the final cert).
	    if (remainingCerts < certs.length) {
		issuerCertImpl = X509CertImpl.toImpl(
		    (X509Certificate)(certs[remainingCerts]));
		seekIssuerCert = false; // done
		
		// By default, the OCSP responder's cert is the same as the 
		// issuer of the cert being validated.
		if (! seekResponderCert) {
		    responderCert = certs[remainingCerts];
		    if (DEBUG != null) {
			DEBUG.println("Responder's certificate is the same " +
			    "as the issuer of the certificate being validated");
		    }
		}
	    }

	    // Check anchor certs for:
	    //    - the issuer cert (of the cert being validated)
	    //    - the OCSP responder's cert
	    if (seekIssuerCert || seekResponderCert) {

		if (DEBUG != null && seekResponderCert) {
		    DEBUG.println("Searching trust anchors for responder's " +
			"certificate");
		}

		// Extract the anchor certs
                Iterator anchors = pkixParams.getTrustAnchors().iterator();
                if (! anchors.hasNext()) {
		    throw new CertPathValidatorException(
			"Must specify at least one trust anchor");
		}

		X500Principal certIssuerName =
		    currCertImpl.getIssuerX500Principal();
		while (anchors.hasNext() &&
			(seekIssuerCert || seekResponderCert)) {

		    TrustAnchor anchor = (TrustAnchor)anchors.next();
		    X509Certificate anchorCert = anchor.getTrustedCert();
		    X500Principal anchorSubjectName =
			anchorCert.getSubjectX500Principal();

		    if (dump) {
			System.out.println("Issuer DN is " + certIssuerName);
			System.out.println("Subject DN is " +
			    anchorSubjectName);
		    }

		    // Check if anchor cert is the issuer cert
		    if (seekIssuerCert &&
			certIssuerName.equals(anchorSubjectName)) {

			issuerCertImpl = X509CertImpl.toImpl(anchorCert);
			seekIssuerCert = false; // done

			// By default, the OCSP responder's cert is the same as
			// the issuer of the cert being validated.
			if (! seekResponderCert && responderCert == null) {
			    responderCert = anchorCert;
			    if (DEBUG != null) {
				DEBUG.println("Responder's certificate is the" +
				    " same as the issuer of the certificate " +
				    "being validated");
			    }
			}
		    }

		    // Check if anchor cert is the responder cert
		    if (seekResponderCert) {
			// Satisfy the responder subject name property only, or
			// satisfy the responder issuer name and serial number 
			// properties only
			if ((responderSubjectName != null &&
			     responderSubjectName.equals(anchorSubjectName)) ||
			    (responderIssuerName != null &&
			     responderSerialNumber != null &&
			     responderIssuerName.equals(
				anchorCert.getIssuerX500Principal()) &&
			     responderSerialNumber.equals(
				anchorCert.getSerialNumber()))) {

			    responderCert = anchorCert;
			    seekResponderCert = false; // done
			}
		    }
		}
		if (issuerCertImpl == null) {
		    throw new CertPathValidatorException(
			"No trusted certificate for " + 
			currCertImpl.getIssuerDN());
		}

		// Check cert stores if responder cert has not yet been found
		if (seekResponderCert) {
		    if (DEBUG != null) {
			DEBUG.println("Searching cert stores for responder's " +
			    "certificate");
		    }
		    X509CertSelector filter = null;
		    if (responderSubjectName != null) {
			filter = new X509CertSelector();
			filter.setSubject(responderSubjectName.getName());
		    } else if (responderIssuerName != null &&
			responderSerialNumber != null) {
			filter = new X509CertSelector();
			filter.setIssuer(responderIssuerName.getName());
			filter.setSerialNumber(responderSerialNumber);
		    }
		    if (filter != null) {
			List<CertStore> certStores = pkixParams.getCertStores();
			for (CertStore certStore : certStores) {
			    Iterator i =
				certStore.getCertificates(filter).iterator();
			    if (i.hasNext()) {
				responderCert = (X509Certificate) i.next();
				seekResponderCert = false; // done
				break;
			    }
			}
		    }
		}
	    }

	    // Could not find the certificate identified in the OCSP properties
	    if (seekResponderCert) {
		throw new CertPathValidatorException(
		    "Cannot find the responder's certificate " +
		    "(set using the OCSP security properties).");
	    }

	    // Construct an OCSP Request
	    OCSPRequest ocspRequest =
		new OCSPRequest(currCertImpl, issuerCertImpl);

	    // Use the URL to the OCSP service that was created earlier
	    HttpURLConnection con = (HttpURLConnection)url.openConnection();
	    if (DEBUG != null) {
		DEBUG.println("connecting to OCSP service at: " + url);
	    }
	    
	    // Indicate that both input and output will be performed, 
	    // that the method is POST, and that the content length is 
	    // the length of the byte array
	    
	    con.setDoOutput(true);
	    con.setDoInput(true);
	    con.setRequestMethod("POST");
	    con.setRequestProperty("Content-type", "application/ocsp-request");
	    byte[] bytes = ocspRequest.encodeBytes();
	    CertId certId = ocspRequest.getCertId();

	    con.setRequestProperty("Content-length",
		String.valueOf(bytes.length));
	    OutputStream out = con.getOutputStream();
	    out.write(bytes);
	    out.flush();

	    // Check the response
	    if (DEBUG != null &&
		con.getResponseCode() != HttpURLConnection.HTTP_OK) {
		DEBUG.println("Received HTTP error: " + con.getResponseCode() +
		    " - " + con.getResponseMessage());
	    }
	    InputStream in = con.getInputStream();

	    int contentLength = con.getContentLength();
	    if (contentLength == -1) {
		contentLength = Integer.MAX_VALUE;
	    }

	    byte[] response = new byte[contentLength];
	    int total = 0;
	    int count = 0;
	    while (count != -1 && total < contentLength) {
	        count = in.read(response, total, response.length - total);
	        total += count;
	    }

	    // clean-up
	    in.close();
	    out.close();

	    OCSPResponse ocspResponse = new OCSPResponse(response, pkixParams,
		responderCert);
	    // Check that response applies to the cert that was supplied
	    if (! certId.equals(ocspResponse.getCertId())) {
		throw new CertPathValidatorException(
		    "Certificate in the OCSP response does not match the " +
		    "certificate supplied in the OCSP request.");
	    }
	    SerialNumber serialNumber = currCertImpl.getSerialNumberObject();
	    int certOCSPStatus = ocspResponse.getCertStatus(serialNumber);

	    if (DEBUG != null) {
		DEBUG.println("Status of certificate (with serial number " +
		    serialNumber.getNumber() + ") is: " + 
		    OCSPResponse.certStatusToText(certOCSPStatus));
	    }
	
	    if (certOCSPStatus == OCSPResponse.CERT_STATUS_REVOKED) {
		throw  new CertificateRevokedException(
		    "Certificate has been revoked", cp, remainingCerts - 1);

	    } else if (certOCSPStatus == OCSPResponse.CERT_STATUS_UNKNOWN) {
		throw  new CertPathValidatorException(
		    "Certificate's revocation status is unknown", null, cp,
		    remainingCerts - 1);
	    } 
	} catch (CertificateRevokedException cre) {
	    throw cre;
	} catch (CertPathValidatorException cpve) {
	    throw cpve;
	} catch (Exception e) {
	    throw new CertPathValidatorException(e);
	}
    }

    /*
     * The OCSP security property values are in the following order:
     *   1. ocsp.responderURL
     *   2. ocsp.responderCertSubjectName
     *   3. ocsp.responderCertIssuerName
     *   4. ocsp.responderCertSerialNumber
     */
    private static URL getOCSPServerURL(X509CertImpl currCertImpl,
	String[] properties)
	throws CertificateParsingException, CertPathValidatorException {
	 
	if (properties[0] != null) {
	   try {
		return new URL(properties[0]);
	   } catch (java.net.MalformedURLException e) {
		throw new CertPathValidatorException(e);
	   }
	}

	// Examine the certificate's AuthorityInfoAccess extension

	AuthorityInfoAccessExtension aia =
	    currCertImpl.getAuthorityInfoAccessExtension(); 
	if (aia == null) {
	    throw new CertPathValidatorException(
		"Must specify the location of an OCSP Responder");
	}
	List<AccessDescription> descriptions;
	try {
	    descriptions =
		(List) aia.get(AuthorityInfoAccessExtension.DESCRIPTIONS);

	    for (AccessDescription description : descriptions) {
		if (description.getAccessMethod().equals(
		    AccessDescription.Ad_OCSP_Id)) {

		    GeneralName generalName = description.getAccessLocation();
		    if (generalName.getType() == 
			GeneralNameInterface.NAME_URI) {

			try {
			    URIName uri = (URIName) generalName.getName();
			    return (new URL(uri.getName()));

			} catch (java.net.MalformedURLException e) {
			    throw new CertPathValidatorException(e);
			}
		    }
		}
	    }
	} catch (IOException e) {
	    // ignore
	}

	throw new CertPathValidatorException(
	    "Cannot find the location of the OCSP Responder");
    }

    /*
     * Retrieves the values of the OCSP security properties.
     */
    private static String[] getOCSPProperties() {
	final String[] properties = new String[4];

	AccessController.doPrivileged(
	    new PrivilegedAction() {
		public Object run() {
		    properties[0] = Security.getProperty(OCSP_URL_PROP);
		    properties[1] =
			Security.getProperty(OCSP_CERT_SUBJECT_PROP);
		    properties[2] =
			Security.getProperty(OCSP_CERT_ISSUER_PROP);
		    properties[3] =
			Security.getProperty(OCSP_CERT_NUMBER_PROP);
		    return null;
		}
	    });

	return properties;
    }

    /*
     * Removes any non-hexadecimal characters from a string.
     */
    private static String stripOutSeparators(String value) {
	char[] chars = value.toCharArray();
	StringBuilder hexNumber = new StringBuilder();
	for (int i = 0; i < chars.length; i++) {
	    if (HEX_DIGITS.indexOf(chars[i]) != -1) {
		hexNumber.append(chars[i]);
	    }
	}
	return hexNumber.toString();
    }
}

/**
 * Indicates that the identified certificate has been revoked.
 */
final class CertificateRevokedException extends 
    CertPathValidatorException {

    CertificateRevokedException(String msg, CertPath certPath, int index) {
	super(msg, null, certPath, index);
    }
}
