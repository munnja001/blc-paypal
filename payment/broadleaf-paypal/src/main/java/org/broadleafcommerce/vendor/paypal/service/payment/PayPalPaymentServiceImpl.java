/*
 * Copyright 2008-2009 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.broadleafcommerce.vendor.paypal.service.payment;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.NameValuePair;
import org.apache.commons.httpclient.methods.PostMethod;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.broadleafcommerce.common.vendor.service.exception.PaymentException;
import org.broadleafcommerce.common.vendor.service.type.ServiceStatusType;
import org.broadleafcommerce.profile.core.service.IdGenerationService;
import org.broadleafcommerce.vendor.paypal.service.payment.message.PayPalErrorResponse;
import org.broadleafcommerce.vendor.paypal.service.payment.message.PayPalItemRequest;
import org.broadleafcommerce.vendor.paypal.service.payment.message.PayPalPaymentRequest;
import org.broadleafcommerce.vendor.paypal.service.payment.message.PayPalPaymentResponse;
import org.broadleafcommerce.vendor.paypal.service.payment.type.PayPalMethodType;

/**
 * 
 * @author jfischer
 *
 */
public class PayPalPaymentServiceImpl implements PayPalPaymentService {
	
	private static final Log LOG = LogFactory.getLog(PayPalPaymentServiceImpl.class);

    protected String user;
    protected String password;
    protected String signature;
    protected String serverUrl;
    protected String libVersion;
    protected Integer failureReportingThreshold;
    protected Integer failureCount = 0;
    protected Boolean isUp = true;
    protected IdGenerationService idGenerationService;

    protected synchronized void clearStatus() {
        isUp = true;
        failureCount = 0;
    }

    protected synchronized void incrementFailure() {
        if (failureCount >= failureReportingThreshold) {
            isUp = false;
        } else {
            failureCount++;
        }
    }

    @Override
    public PayPalPaymentResponse process(PayPalPaymentRequest paymentRequest) throws PaymentException {
        String response;
        try {
            response = communicateWithVendor(paymentRequest);
        } catch (Exception e) {
            incrementFailure();
            throw new PaymentException(e);
        }
        clearStatus();
        
        PayPalPaymentResponse payPalPaymentResponse = new PayPalPaymentResponse();
        payPalPaymentResponse.setTransactionType(paymentRequest.getTransactionType());
        payPalPaymentResponse.setMethodType(paymentRequest.getMethodType());
        
        return buildResponse(response, payPalPaymentResponse);
    }
    
    protected PayPalPaymentResponse buildResponse(String rawResponse, PayPalPaymentResponse response) {
        String ack = getResponseValue(rawResponse, MessageConstants.ACK);
        if (ack.toLowerCase().equals(MessageConstants.SUCCESS)) {
            response.setErrorDetected(false);
            response.setSuccessful(true);
            response.setResponseToken(getResponseValue(rawResponse, MessageConstants.TOKEN));
        } else if (ack.toLowerCase().equals(MessageConstants.SUCCESSWITHWARNINGS)) {
            response.setSuccessful(true);
            response.setErrorDetected(true);
            response.setResponseToken(getResponseValue(rawResponse, MessageConstants.TOKEN));
        } else {
            response.setSuccessful(false);
            response.setErrorDetected(true);
        }
        if (response.isErrorDetected()) {
            boolean eof = false;
            int errorNumber = 0;
            while (!eof) {
                String errorCode = getResponseValue(rawResponse, MessageConstants.ERRORCODE + errorNumber);
                if (errorCode != null) {
                    PayPalErrorResponse errorResponse = new PayPalErrorResponse();
                    errorResponse.setErrorCode(errorCode);
                    errorResponse.setShortMessage(getResponseValue(rawResponse, MessageConstants.ERRORSHORTMESSAGE + errorNumber));
                    errorResponse.setLongMessage(getResponseValue(rawResponse, MessageConstants.ERRORLONGMESSAGE + errorNumber));
                    errorResponse.setSeverityCode(getResponseValue(rawResponse, MessageConstants.ERRORSEVERITYCODE + errorNumber));
                    response.getErrorResponses().add(errorResponse);
                } else {
                    eof = true;
                }
                errorNumber++;
            }

            errorNumber = 0;
            eof = false;
            while (!eof) {
                String passThroughErrorName = getResponseValue(rawResponse, MessageConstants.ERRORPASSTHROUGHNAME + errorNumber);
                if (passThroughErrorName != null) {
                    response.getPassThroughErrors().put(passThroughErrorName, getResponseValue(rawResponse, MessageConstants.ERRORPASSTHROUGHVALUE + errorNumber));
                } else {
                    eof = true;
                }
                errorNumber++;
            }
        }
        return null;
    }

    protected String communicateWithVendor(PayPalPaymentRequest paymentRequest) throws IOException {
        HttpClient httpClient = new HttpClient();
        PostMethod postMethod = new PostMethod(getServerUrl());
        List<NameValuePair> nvps = new ArrayList<NameValuePair>();
        if (paymentRequest.getMethodType() == PayPalMethodType.CHECKOUT) {
            setNvpsForCheckout(nvps, paymentRequest);
        }
        //TODO implement the other methods
        /*if(method.equals("checkout")) {
            setNvpsForCheckout(nvps, order);
        } else if(method.equals("details")) {
            setNvpsForDetails(nvps);
        } else {
            setNvpsForProcess(nvps, order);
        }*/
        postMethod.setRequestBody(nvps.toArray(new NameValuePair[nvps.size()]));
        httpClient.executeMethod(postMethod);
        return postMethod.getResponseBodyAsString();
    }
    
    protected void setNvpsForCheckout(List<NameValuePair> nvps, PayPalPaymentRequest paymentRequest) {
        nvps.add(new NameValuePair(MessageConstants.USER, user));
        nvps.add(new NameValuePair(MessageConstants.PASSWORD, password));
        nvps.add(new NameValuePair(MessageConstants.SIGNATURE, signature));
        nvps.add(new NameValuePair(MessageConstants.VERSION, libVersion));
        nvps.add(new NameValuePair(MessageConstants.PAYMENTACTION, MessageConstants.SALEACTION));

        setCostNvps(nvps, paymentRequest);

        nvps.add(new NameValuePair(MessageConstants.RETURNURL, paymentRequest.getParamsRequest().getReturnUrl()));
        nvps.add(new NameValuePair(MessageConstants.CANCELURL, paymentRequest.getParamsRequest().getCancelUrl()));
        for (Map.Entry<String, String> entry : paymentRequest.getParamsRequest().getAdditionalParams().entrySet()) {
            nvps.add(new NameValuePair(entry.getKey(), entry.getValue()));
        }
        nvps.add(new NameValuePair(MessageConstants.METHOD, MessageConstants.EXPRESSCHECKOUTACTION));
    }
    
    protected void setCostNvps(List<NameValuePair> nvps, PayPalPaymentRequest paymentRequest) {
        int counter = 0;
        for (PayPalItemRequest itemRequest : paymentRequest.getItemRequests()) {
            nvps.add(new NameValuePair(MessageConstants.NAMEREQUEST + counter, itemRequest.getShortDescription()));
            nvps.add(new NameValuePair(MessageConstants.NUMBERREQUEST + counter, itemRequest.getSystemId()));
            nvps.add(new NameValuePair(MessageConstants.DESCRIPTIONREQUEST + counter, itemRequest.getDescription()));
            nvps.add(new NameValuePair(MessageConstants.AMOUNTREQUEST + counter, itemRequest.getUnitPrice().toString()));
            nvps.add(new NameValuePair(MessageConstants.QUANTITYREQUEST + counter, String.valueOf(itemRequest.getQuantity())));
            counter++;
        }
        nvps.add(new NameValuePair(MessageConstants.SUBTOTALREQUEST, paymentRequest.getSummaryRequest().getSubTotal().toString()));
        nvps.add(new NameValuePair(MessageConstants.TAXREQUEST, paymentRequest.getSummaryRequest().getTotalTax().toString()));
        nvps.add(new NameValuePair(MessageConstants.SHIPPINGREQUEST, paymentRequest.getSummaryRequest().getTotalShipping().toString()));
        nvps.add(new NameValuePair(MessageConstants.SHIPPINGDISCOUNTREQUEST, "-" + paymentRequest.getSummaryRequest().getShippingDiscount().toString()));
        nvps.add(new NameValuePair(MessageConstants.GRANDTOTALREQUEST, paymentRequest.getSummaryRequest().getGrandTotal().toString()));
    }

    protected String getResponseValue(String resp, String valueName) {
        int keyBegin = resp.indexOf(valueName);
        if (keyBegin >= 0) {
            int tokenBegin = keyBegin + valueName.length() + 1;
            int tokenEnd = resp.indexOf('&', tokenBegin);
            return resp.substring(tokenBegin, tokenEnd);
        }
        return null;
    }

    public Integer getFailureReportingThreshold() {
        return failureReportingThreshold;
    }

    public String getUser() {
        return user;
    }

    public String getServerUrl() {
        return serverUrl;
    }

    public synchronized ServiceStatusType getServiceStatus() {
        if (isUp) {
            return ServiceStatusType.UP;
        } else {
            return ServiceStatusType.DOWN;
        }
    }

    public void setFailureReportingThreshold(Integer failureReportingThreshold) {
        this.failureReportingThreshold = failureReportingThreshold;
    }

    public void setUser(String user) {
        this.user = user;
    }

    public void setServerUrl(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    public IdGenerationService getIdGenerationService() {
        return idGenerationService;
    }

    public void setIdGenerationService(IdGenerationService idGenerationService) {
        this.idGenerationService = idGenerationService;
    }
    
    public String getServiceName() {
        return getClass().getName();
    }

    public String getLibVersion() {
        return libVersion;
    }

    public void setLibVersion(String libVersion) {
        this.libVersion = libVersion;
    }

    public String getSignature() {
        return signature;
    }

    public void setSignature(String signature) {
        this.signature = signature;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
