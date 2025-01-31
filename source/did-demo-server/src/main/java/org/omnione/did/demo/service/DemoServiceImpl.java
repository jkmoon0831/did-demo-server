/*
 * Copyright 2024 OmniOne.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.omnione.did.demo.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.google.zxing.WriterException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.omnione.did.crypto.enums.MultiBaseType;
import org.omnione.did.crypto.util.MultiBaseUtils;
import org.omnione.did.demo.api.CasFeign;
import org.omnione.did.demo.api.IssuerFeign;
import org.omnione.did.demo.api.TasFeign;
import org.omnione.did.demo.api.VerifierFeign;
import org.omnione.did.base.exception.ErrorCode;
import org.omnione.did.base.exception.OpenDidException;
import org.omnione.did.demo.dto.*;
import org.omnione.did.base.property.DemoProperty;
import org.omnione.did.demo.util.BaseDigestUtil;
import org.omnione.did.demo.util.BaseMultibaseUtil;
import org.omnione.did.demo.util.HexUtil;
import org.omnione.did.demo.util.JsonUtil;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.UUID;

import static org.omnione.did.demo.util.QrMaker.*;

/**
 * This is a sample implementation for testing purposes.
 * This implementation does not actually perform any verification, but instead returns dummy data.
 */
@RequiredArgsConstructor
@Slf4j
@Service
@Profile("!sample")
public class DemoServiceImpl implements DemoService{
    private final VerifierFeign verifierFeign;
    private final TasFeign tasFeign;
    private final CasFeign casFeign;
    private final IssuerFeign issuerFeign;
    private final DemoProperty demoProperty;

    /**
     * Refreshes the Verifiable Presentation (VP) offer.
     * This method requests a VP offer from the Verifier and returns the offer as a QR code.
     *
     * @return VpResultDto containing the VP offer as a QR code
     */
    @Override
    public VpResultDto vpOfferRefresh(){
        log.debug("=== Starting VpOfferRefresh ===");
        log.debug("\t Make VP Offer Request");
        try {
            RequestVpOfferReqDto offerReqDto = RequestVpOfferReqDto.builder()
                    .mode(demoProperty.getMode())
                    .device(demoProperty.getDevice())
                    .service(demoProperty.getService())
                    .build();
            log.debug("\t Request VP Offer QR");
            RequestVpOfferResDto requestVpOfferResDto = verifierFeign.requestVpOfferQR(offerReqDto);
            if (requestVpOfferResDto == null) {
                throw new OpenDidException(ErrorCode.VP_OFFER_NOT_FOUND);
            }
            log.debug("\t Serialize and Encode VP Offer Payload");
            String jsonString = JsonUtil.serializeAndSort(requestVpOfferResDto.getPayload());
            String encDataPayload = BaseMultibaseUtil.encode(jsonString.getBytes(), MultiBaseType.base64);
            VpResultDto vpResultDto = VpResultDto.builder()
                    .payloadType("SUBMIT_VP")
                    .payload(encDataPayload)
                    .validUntil(requestVpOfferResDto.getPayload().getValidUntil())
                    .build();
            log.debug("\t Make QR Image");
            QrImageData qrImageData = makeQrImage(vpResultDto);
            vpResultDto.setQrImage(qrImageData.getQrIamge());
            vpResultDto.setOfferId(requestVpOfferResDto.getPayload().getOfferId());
            log.debug("=== End VpOfferRefresh ===");
            return vpResultDto;

        } catch (OpenDidException e){
            log.error("OpenDidException occurred during Requesting VP Offer: {}", e.getErrorCode().getMessage());
            throw e;
        } catch (JsonProcessingException e) {
            log.error("JsonProcessingException occurred during Requesting VP Offer: {}", e.getMessage());
            throw new OpenDidException(ErrorCode.JSON_PROCESSING_ERROR);
        } catch (Exception e) {
            log.error("Exception occurred during Requesting VP Offer: {}", e.getMessage(), e);
            throw new OpenDidException(ErrorCode.UNKNOWN_SERVER_ERROR);
        }

    }

    /**
     * Refreshes the Verifiable Credential (VC) offer.
     * This method requests a VC offer from the TAS and returns the offer as a QR code.
     *
     * @return VcResultDto containing the VC offer as a QR code
     */
    @Override
    public VcResultDto vcOfferRefresh() throws IOException, WriterException {
        log.debug("=== Starting VcOfferRefresh ===");
        log.debug("\t Make VC Offer Request");
        RequestVcOfferReqDto vcOfferReqDto = RequestVcOfferReqDto.builder()
                .vcPlanId(demoProperty.getVcPlanId())
                .issuer(demoProperty.getIssuer())
                .build();
        log.debug("\t Request VC Offer to TAS");
        RequestVcOfferResDto requestVcOfferResDto = tasFeign.requestVcOfferQR(vcOfferReqDto);
        String jsonString = JsonUtil.serializeAndSort(requestVcOfferResDto.getIssueOfferPayload());
        String encDataPayload = BaseMultibaseUtil.encode(jsonString.getBytes(), MultiBaseType.base64);
        VcResultDto vcResultDto = VcResultDto.builder()
                .payloadType("ISSUE_VC")
                .payload(encDataPayload)
                .build();
        log.debug("\t Make VcPayload data to QR Image");
        QrImageData qrImageData = makeQrImage(vcResultDto);
        vcResultDto.setQrImage(qrImageData.getQrIamge());
        vcResultDto.setValidUntil(requestVcOfferResDto.getValidUntil());
        vcResultDto.setOfferId(requestVcOfferResDto.getOfferId());
        log.debug("=== End VcOfferRefresh ===");
        return vcResultDto;
    }

    /**
     * Submits the Verifiable Credential (VC) offer.
     * This method submits the VC offer to the TAS and returns the result.
     *
     * @param requestVcSubmitReqDto the VC offer to submit
     * @return RequestVcSubmitResDto containing the result of the submission
     */
    @Override
    public RequestVcSubmitResDto vcOfferSubmit(RequestVcSubmitReqDto requestVcSubmitReqDto) {
        log.debug("requestVcSubmitReqDto : {}", requestVcSubmitReqDto);
        return tasFeign.requestVcSubmitConfirm(requestVcSubmitReqDto);
    }

    /**
     * Pushes the Verifiable Credential (VC) offer.
     * This method pushes the VC offer to the TAS and returns the result.
     *
     * @param requestVcOfferReqDto the VC offer to push
     * @return VcOfferPushResDto containing the result of the push
     */
    @Override
    public VcOfferPushResDto vcOfferPush(RequestVcOfferReqDto requestVcOfferReqDto) {
        log.debug("requestVcOfferReqDto : {}", requestVcOfferReqDto);
        String messageId = new UUID(new SecureRandom().nextLong(), new SecureRandom().nextLong()).toString().substring(0, 8);
        VcOfferPushResDto vcOfferPushResDto = tasFeign.requestVcOfferPush(RequestVcOfferReqDto.builder()
                .holder(requestVcOfferReqDto.getDid())
                .issuer(demoProperty.getIssuer())
                .id(messageId)
                .vcPlanId(demoProperty.getVcPlanId())
                .build());
        if(vcOfferPushResDto != null){
            vcOfferPushResDto.setResult("success");
        }
        log.debug("requestVcOfferReqDto : {}", requestVcOfferReqDto);
        return vcOfferPushResDto;
    }

    /**
     * Sends the Verifiable Credential (VC) offer via email.
     * This method sends the VC offer to the email address provided and returns the result.
     *
     * @param requestVcOfferReqDto the VC offer to send
     * @return RequestVcOfferResDto containing the result of the email send
     */
    @Override
    public RequestVcOfferResDto vcOfferEmail(RequestVcOfferReqDto requestVcOfferReqDto) {
        RequestVcOfferResDto requestVcOfferResDto =
                tasFeign.requestVcOfferEmail(RequestVcOfferReqDto.builder()
                .email(requestVcOfferReqDto.getEmail())
                .vcPlanId(demoProperty.getVcPlanId())
                .issuer(demoProperty.getIssuer())
                .build());

        if(requestVcOfferResDto != null){
            requestVcOfferResDto.setResult(true);
        }
        return requestVcOfferResDto;
    }

    /**
     * Saves the user information.
     * This method saves the user information to the CAS and returns the result.
     *
     * @param saveUserInfoReqDto the user information to save
     * @return SaveUserInfoResDto containing the result of the save
     */
    @Override
    public SaveUserInfoResDto saveUserInfo(SaveUserInfoReqDto saveUserInfoReqDto) {
        try {
            String json = JsonUtil.serializeAndSort(SerializeUserInfoData.builder()
                            .firstname(saveUserInfoReqDto.getFirstname())
                            .lastname(saveUserInfoReqDto.getLastname())
                            .build());
            byte[] hashedDataBytes = BaseDigestUtil.generateHash(json.getBytes(StandardCharsets.UTF_8));
            String encode = BaseMultibaseUtil.encode(hashedDataBytes, MultiBaseType.base64);
            log.info("encode : {}", encode);
            String hexStringPii = HexUtil.toHexString(hashedDataBytes);
            saveUserInfoReqDto.setPii(hexStringPii);

            SecureRandom random = new SecureRandom();
            String userId = new UUID(random.nextLong(), random.nextLong()).toString().substring(0, 8);

            casFeign.saveUserInfo(SaveUserInfoResDto.builder()
                    .userId(userId)
                    .pii(hexStringPii)
                    .build());

            issuerFeign.saveUserInfo(saveUserInfoReqDto);

            return SaveUserInfoResDto.builder()
                    .userId(userId)
                    .pii(hexStringPii)
                    .build();

        } catch (JsonProcessingException e) {
            log.error("Json Processing error : " + e.getMessage());
            throw new OpenDidException(ErrorCode.JSON_PROCESSING_ERROR);
        } catch (Exception e) {
            log.error("saveUserInfo error : {}", e.getMessage());
            throw new OpenDidException(ErrorCode.VC_SAVE_FAILED);
        }
    }


    /**
     * Saves the Verifiable Credential (VC) information.
     * This method saves the VC information to the CAS and returns the result.
     *
     * @param saveVcInfoReqDto the VC information to save
     * @return SaveUserInfoResDto containing the result of the save
     */
    @Override
    public SaveUserInfoResDto saveVcInfo(SaveVcInfoReqDto saveVcInfoReqDto) {
        log.info("saveVcInfoReqDto : {}", saveVcInfoReqDto.toString());
        try {
            if (saveVcInfoReqDto.getDid() == null || saveVcInfoReqDto.getDid().isEmpty()) {
                throw new OpenDidException(ErrorCode.VC_INVALID_FORMAT);
            }
            issuerFeign.saveVcInfo(saveVcInfoReqDto);
            return SaveUserInfoResDto.builder()
                    .result(true)
                    .build();
        } catch (OpenDidException e) {
            throw e;
        } catch (Exception e) {
            log.error("saveVcInfo error : {}", e.getMessage(), e);
            throw new OpenDidException(ErrorCode.VC_SAVE_FAILED);
        }
    }

    /**
     * Issues the Verifiable Credential (VC) result.
     * This method issues the VC result to the CAS and returns the result.
     *
     * @param issueVcResultReqDto the VC result to issue
     * @return IssueVcResultResDto containing the result of the issue
     */
    @Override
    public IssueVcResultResDto issueVcResult(IssueVcResultReqDto issueVcResultReqDto) {

        return issuerFeign.issueVcResult(issueVcResultReqDto.getOfferId());
    }

    /**
     * Confirms the verification.
     * This method confirms the verification and returns the result.
     *
     * @param confirmVerifyReqDto the verification to confirm
     * @return ConfirmVerifyResDto containing the result of the confirmation
     */
    @Override
    public ConfirmVerifyResDto confirmVerify(ConfirmVerifyReqDto confirmVerifyReqDto) {
        return verifierFeign.confirmVerify(confirmVerifyReqDto);
    }

}



