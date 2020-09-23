package com.dicoding.FinalProject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.LineSignatureValidator;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.Multicast;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.action.MessageAction;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.*;
import com.linecorp.bot.model.event.source.GroupSource;
import com.linecorp.bot.model.event.source.RoomSource;
import com.linecorp.bot.model.message.*;
import com.linecorp.bot.model.PushMessage;
import com.linecorp.bot.model.message.flex.container.FlexContainer;
import com.linecorp.bot.model.message.template.CarouselColumn;
import com.linecorp.bot.model.message.template.CarouselTemplate;
import com.linecorp.bot.model.objectmapper.ModelObjectMapper;
import com.linecorp.bot.model.profile.UserProfileResponse;
import org.apache.commons.io.IOUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.concurrent.ExecutionException;

@RestController
class Controller {


    @Autowired
    @Qualifier("lineMessagingClient")
    private LineMessagingClient lineMessagingClient;


    @Autowired
    @Qualifier("lineSignatureValidator")
    private LineSignatureValidator lineSignatureValidator;

    //Eksekusi reply pesan kepada adder, yang mengirimkan pesan ke OA bot ini
    @RequestMapping(value="/webhook", method= RequestMethod.POST)
    public ResponseEntity<String> callback(
            @RequestHeader("X-Line-Signature") String xLineSignature,
            @RequestBody String eventsPayload)
    {
        try {
            if (!lineSignatureValidator.validateSignature(eventsPayload.getBytes(), xLineSignature)) {
                throw new RuntimeException("Invalid Signature Validation");
            }

            ObjectMapper objectMapper = ModelObjectMapper.createNewObjectMapper();
            EventsModel eventsModel = objectMapper.readValue(eventsPayload, EventsModel.class);

            eventsModel.getEvents().forEach((event)->{
                if (event instanceof MessageEvent) {
                    if (event.getSource() instanceof GroupSource || event.getSource() instanceof RoomSource) {
                        // Method apabila Bot berada di dalam groupchat
                        handleGroupRoomChats((MessageEvent) event);
                    } else {
                        // Method apabila Bot berada di luar groupchat atau personal chat ke Bot
                        handleOneOnOneChats((MessageEvent) event);
                    }
                }
            });
            return new ResponseEntity<>(HttpStatus.OK);

        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    private void handleOneOnOneChats(MessageEvent event) {
        if  (event.getMessage() instanceof AudioMessageContent
                || event.getMessage() instanceof ImageMessageContent
                || event.getMessage() instanceof VideoMessageContent
                || event.getMessage() instanceof FileMessageContent
        ) {
            handleContentMessage(event);
        } else if(event.getMessage() instanceof TextMessageContent) {
            handleTextMessage(event);
        } else if (event.getMessage() instanceof StickerMessageContent) {
            replySticker(event.getReplyToken(), "1","125");
        }
        else {
            replyText(event.getReplyToken(), "Unknown Message");
        }
    }

    private void handleTextMessage(MessageEvent event) {
        MessageEvent messageEvent = event;
        TextMessageContent textMessageContent = (TextMessageContent) messageEvent.getMessage();
        // Conditional apakah event mendapatkan pesan "flex message" atau tidak
        if (textMessageContent.getText().toLowerCase().contains("detail")) {
            replyFlexMessage(event.getReplyToken(), textMessageContent.getText().toLowerCase());
        } else if (textMessageContent.getText().toLowerCase().contains("#init")) {
            TemplateMessage carouselEvent = replyCarouselMessage();
            ReplyMessage replyMessage = new ReplyMessage(event.getReplyToken(), carouselEvent);
            reply(replyMessage);
        }

        else {
            List<Message> msgArray = new ArrayList<>();
            msgArray.add(new TextMessage(textMessageContent.getText()));
            msgArray.add(new StickerMessage("1", "106"));
            ReplyMessage replyMessage = new ReplyMessage(messageEvent.getReplyToken(), msgArray);
            reply(replyMessage);
        }
    }

    private void handleContentMessage(MessageEvent event) {
        String baseURL     = "https://botjavatest.herokuapp.com";
        String contentURL  = baseURL+"/content/"+ event.getMessage().getId();
        String contentType = event.getMessage().getClass().getSimpleName();
        String textMsg     = contentType.substring(0, contentType.length() -14)
                + " yang kamu kirim bisa diakses dari link:\n "
                + contentURL;

        replyText(event.getReplyToken(), textMsg);
    }

    private void handleGroupRoomChats(MessageEvent event) {
        if(!event.getSource().getUserId().isEmpty()) {
            String userId = event.getSource().getUserId();
            UserProfileResponse profile = getProfile(userId);
            replyText(event.getReplyToken(), "Hello, " + profile.getDisplayName());
        } else {
            replyText(event.getReplyToken(), "Hello, what is your name?");
        }
    }


    // Eksekusi untuk melakukan push message, berdasarkan User ID yang dimiliki
    @RequestMapping(value="/pushmessage/{id}/{message}", method=RequestMethod.GET)
    public ResponseEntity<String> pushmessage(
            @PathVariable("id") String userId,
            @PathVariable("message") String textMsg
    ){
        TextMessage textMessage = new TextMessage(textMsg);
        PushMessage pushMessage = new PushMessage(userId, textMessage);
        push(pushMessage);

        return new ResponseEntity<String>("Push message:"+textMsg+"\nsent to: "+userId, HttpStatus.OK);
    }

    // Eksekusi untuk melakukan multicast message, dari beberapa user
    @RequestMapping(value="/multicast", method=RequestMethod.GET)
    public ResponseEntity<String> multicast(){
        String[] userIdList = {
                "U36635aea0b7478415cb72eb5a55cbd45"};
        Set<String> listUsers = new HashSet<String>(Arrays.asList(userIdList));
        if(listUsers.size() > 0){
            String textMsg = "Ini pesan multicast";
            sendMulticast(listUsers, textMsg);
        }
        return new ResponseEntity<String>(HttpStatus.OK);
    }

    // Eksekusi untuk melakukan profile getter, dari suatu user
    @RequestMapping(value = "/profile/{id}", method = RequestMethod.GET)
    public ResponseEntity<String> profile(
            @PathVariable("id") String userId
    ){
        UserProfileResponse profile = getProfile(userId);

        if (profile != null) {
            String profileName = profile.getDisplayName();
            TextMessage textMessage = new TextMessage("Hello, " + profileName);
            PushMessage pushMessage = new PushMessage(userId, textMessage);
            push(pushMessage);

            return new ResponseEntity<String>("Hello, "+profileName, HttpStatus.OK);
        }
        return new ResponseEntity<String>(HttpStatus.NOT_FOUND);
    }

    // Eksekusi untuk menerima/mengambil berkas (dengan jenis ekstensi file tertentu) untuk disimpan di heroku
    @RequestMapping(value = "/content/{id}", method = RequestMethod.GET)
    public ResponseEntity content(
            @PathVariable("id") String messageId
    ){
        MessageContentResponse messageContent = getContent(messageId);

        if(messageContent != null) {
            HttpHeaders headers = new HttpHeaders();
            String[] mimeType = messageContent.getMimeType().split("/");
            headers.setContentType(new MediaType(mimeType[0], mimeType[1]));

            InputStream inputStream = messageContent.getStream();
            InputStreamResource inputStreamResource = new InputStreamResource(inputStream);

            return new ResponseEntity<>(inputStreamResource, headers, HttpStatus.OK);
        }

        return new ResponseEntity<>(HttpStatus.NOT_FOUND);
    }

    // Method untuk melakukan push message
    private void push(PushMessage pushMessage){
        try {
            lineMessagingClient.pushMessage(pushMessage).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    // Method untuk melakukan reply message
    private void reply(ReplyMessage replyMessage) {
        try {
            lineMessagingClient.replyMessage(replyMessage).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    // Method untuk melakukan multicast message
    private void sendMulticast(Set<String> sourceUsers, String txtMessage){
        TextMessage message = new TextMessage(txtMessage);
        Multicast multicast = new Multicast(sourceUsers, message);

        try {
            lineMessagingClient.multicast(multicast).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    // Method untuk mendapatkan identity2 pada suatu Line profiles
    private UserProfileResponse getProfile(String userId){
        try {
            return lineMessagingClient.getProfile(userId).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    // Method untuk menerima/mengambil file (Dengan ekstensi tertentu) dari user, dan disimpan di heroku
    private MessageContentResponse getContent(String messageId) {
        try {
            return lineMessagingClient.getMessageContent(messageId).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    private void replyText(String replyToken, String messageToUser){
        TextMessage textMessage = new TextMessage(messageToUser);
        ReplyMessage replyMessage = new ReplyMessage(replyToken, textMessage);
        reply(replyMessage);
    }


    private void replySticker(String replyToken, String packageId, String stickerId){
        StickerMessage stickerMessage = new StickerMessage(packageId, stickerId);
        ReplyMessage replyMessage = new ReplyMessage(replyToken, stickerMessage);
        reply(replyMessage);
    }

    // Method untuk menghasilkan Flex Message
    private void replyFlexMessage(String replyToken, String getKey) {
        try {
            ClassLoader classLoader = getClass().getClassLoader();
            String json = "";
            if (getKey.contains("#walkot_detail")){
                json = "walkot_detail.json";
            } else if (getKey.contains("#wawalkot_detail")){
                json = "wawalkot_detail.json";
            } else if (getKey.contains("summary_govern")){
                json = "carousel_govern.json";
            } else if (getKey.contains("summary_food")){
                json = "carousel_food.json";
            } else if (getKey.contains("summary_travel")){
                json = "carousel_travel.json";
            } else if (getKey.contains("summary_univ")){
                json = "carousel_univ.json";
            } else if (getKey.contains("odading_detail")){
                json = "odading_details.json";
            } else if (getKey.contains("nas_kol_detail")){
                json = "nasi_kalong_details.json";
            } else if (getKey.contains("mart_manis_detail")){
                json = "martabak_manis_detail.json";
            } else if (getKey.contains("mochi_detail")) {
                json = "mochi_details.json";
            } else if (getKey.contains("rmh_sosis_detail")) {
                json = "sosis_details.json";
            } else if (getKey.contains("batagor_detail")) {
                json = "batagor_details.json";
            } else if (getKey.contains("kaa_detail")) {
                json = "kaa_details.json";
            } else if (getKey.contains("gtp_detail")) {
                json = "tang_perahu_details.json";
            } else if (getKey.contains("tahura_detail")) {
                json = "tahura_details.json";
            } else if (getKey.contains("ranca_upas_detail")) {
                json = "rancaupas_details.json";
            } else if (getKey.contains("balkot_detail")) {
                json = "balkot_details.json";
            } else if (getKey.contains("bosscha_detail")) {
                json = "bosscha_details.json";
            } else if (getKey.contains("itb_detail")){
                json = "itb_details.json";
            } else if (getKey.contains("upi_detail")) {
                json = "upi_details.json";
            } else if (getKey.contains("unpas_detail")) {
                json = "unpas_details.json";
            } else if (getKey.contains("unisba_detail")) {
                json = "unisba_detaails.json";
            }
            String flexTemplate = IOUtils.toString(classLoader.getResourceAsStream(json));

            ObjectMapper objectMapper = ModelObjectMapper.createNewObjectMapper();
            FlexContainer flexContainer = objectMapper.readValue(flexTemplate, FlexContainer.class);

            ReplyMessage replyMessage = new ReplyMessage(replyToken, new FlexMessage("Flex Message", flexContainer));
            reply(replyMessage);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private TemplateMessage replyCarouselMessage() {
        CarouselColumn column;
        List<CarouselColumn> carouselColumn = new ArrayList<>();

        String[] carouselImg = {"https://idetrips.com/wp-content/uploads/2018/10/Tempat-Wisata-di-Bandung-Gambar-Gedung-Sate.jpg",
        "https://asset.kompas.com/crops/AoBKmkpfpOBpO5orRY2uDT0NfJM=/0x0:845x563/750x500/data/photo/2020/09/16/5f617e30c4d9a.jpg",
                "https://upload.wikimedia.org/wikipedia/commons/8/84/Musium_KAA.jpg",
                "https://akcdn.detik.net.id/community/media/visual/2020/07/10/kampus-itb-1_169.jpeg?w=700&q=90"};
        String[] carouselTitle = {"Pemerintahan", "Kuliner", "Pariwisata", "Universitas"};
        String[] carouselText = {"Daftar Pemerintah Kota Bandung", "Kuliner Populer di Kota Bandung",
                "Tempat yang Cocok Buat Travellers", "Kampus yang Banyak Dikenal di Kota Bandung"};
        String[] carouselButton = {"#summary_govern", "#summary_food", "#summary_travel","#summary_univ"};

        for (int i = 0; i < carouselImg.length; i++) {
            column = new CarouselColumn(carouselImg[i],carouselTitle[i],carouselText[i], Arrays.asList(
                new MessageAction("Lihat Summary", carouselButton[i])
            )
            );
            carouselColumn.add(column);
        }

        CarouselTemplate carouselTemplate = new CarouselTemplate(carouselColumn);

        return new TemplateMessage("Search result", carouselTemplate);
    }



}