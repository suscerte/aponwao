/*
 * @(#)CertPathHelperImpl.java	1.6 09/02/17
 *
 * Copyright 2006-2009 Sun Microsystems, Inc. All rights reserved.
 * SUN PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 */

package flex.aponwao.core.validator;

import java.security.cert.TrustAnchor;
import java.util.*;
import javax.security.auth.x500.X500Principal;
import sun.security.x509.GeneralNameInterface;

/**
 * Helper class that allows the Sun CertPath provider to access
 * implementation dependent APIs in CertPath framework.
 *
 * @author Andreas Sterbenz
 * @version 1.6, 02/17/09
 */
class CertPathHelperImpl extends CertPathHelper {
    
    private CertPathHelperImpl() {
	// empty
    }
    
    /**
     * Initialize the helper framework. This method must be called from 
     * the static initializer of each class that is the target of one of 
     * the methods in this class. This ensures that the helper if initialized
     * prior to a tunneled call from the Sun provider.
     */
    synchronized static void initialize() {
	if (CertPathHelper.instance == null) {
	    CertPathHelper.instance = new CertPathHelperImpl();
	}
    }
    
    @Override
    protected void implSetSubject(X509CertSelector sel, X500Principal subject) {
	sel.setSubject(subject);
    }

    @Override
    protected X500Principal implGetSubject(X509CertSelector sel) {
	return sel.getSubject();
    }
    
    @Override
    protected void implSetIssuer(X509CertSelector sel, X500Principal issuer) {
	sel.setIssuer(issuer);
    }

    @Override
    protected X500Principal implGetIssuer(X509CertSelector sel) {
	return sel.getIssuer();
    }
    
    @Override
    protected X500Principal implGetCA(TrustAnchor anchor) {
	return anchor.getCA();
    }
    
    @Override
    protected void implSetPathToNames(X509CertSelector sel, 
	    Set<GeneralNameInterface> names) {
	sel.setPathToNamesInternal(names);
    }

    @Override
    protected void implAddIssuer(X509CRLSelector sel, X500Principal name) {
	sel.addIssuer(name);
    }

    @Override
    protected Collection<X500Principal> implGetIssuers(X509CRLSelector sel) {
	return sel.getIssuers();
    }

    @Override
    protected void implSetDateAndTime(X509CRLSelector sel, Date date, long skew) {
        sel.setDateAndTime(date, skew);
    }
}

