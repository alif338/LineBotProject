package com.dicoding.FinalProject;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.linecorp.bot.client.LineMessagingClient;
import com.linecorp.bot.client.LineSignatureValidator;
import com.linecorp.bot.client.MessageContentResponse;
import com.linecorp.bot.model.Multicast;
import com.linecorp.bot.model.ReplyMessage;
import com.linecorp.bot.model.action.MessageAction;
import com.linecorp.bot.model.event.FollowEvent;
import com.linecorp.bot.model.event.JoinEvent;
import com.linecorp.bot.model.event.MessageEvent;
import com.linecorp.bot.model.event.message.*;
import com.linecorp.bot.model.event.source.GroupSource;
import com.linecorp.bot.model.event.source.RoomSource;
import com.linecorp.bot.model.event.source.Source;
import com.linecorp.bot.model.event.source.UserSource;
import com.linecorp.bot.model.message.*;
import com.linecorp.bot.model.PushMessage;
import com.linecorp.bot.model.message.flex.container.FlexContainer;
import com.linecorp.bot.model.message.template.ButtonsTemplate;
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
                } else if (event instanceof FollowEvent || event instanceof JoinEvent) {
                    greetingMessage(event.getSource());
                }
            });
            return new ResponseEntity<>(HttpStatus.OK);

        } catch (IOException e) {
            e.printStackTrace();
            return new ResponseEntity<>(HttpStatus.BAD_REQUEST);
        }
    }

    private void handleOneOnOneChats(MessageEvent event) {
        if(event.getMessage() instanceof TextMessageContent) {
            handleTextMessage(event);
        }
    }

    private void handleTextMessage(MessageEvent event) {
        MessageEvent messageEvent = event;
        TextMessageContent textMessageContent = (TextMessageContent) messageEvent.getMessage();
        // Conditional apakah event mendapatkan pesan "flex message" atau tidak
        if (textMessageContent.getText().toLowerCase().contains("summary")) {
            replyFlexMessage1(event.getReplyToken(), textMessageContent.getText().toLowerCase());
        } else if (textMessageContent.getText().toLowerCase().contains("#init")) {
            TemplateMessage carouselEvent = replyCarouselMessage();
            ReplyMessage replyMessage = new ReplyMessage(event.getReplyToken(), carouselEvent);
            reply(replyMessage);
        } else if (textMessageContent.getText().toLowerCase().contains("detail")) {
            replyFlexMessage2(event.getReplyToken(),textMessageContent.getText().toLowerCase());
        }

    }

    private void handleGroupRoomChats(MessageEvent event) {
        if(!event.getSource().getUserId().isEmpty()) {
            handleTextMessage(event);
        } else {
            replyText(event.getReplyToken(), "Hello, Silahkan menambahkan BOTBabandungan ini sebagai teman :)");
        }
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


    // Method untuk melakukan push message
    private void push(PushMessage pushMessage){
        try {
            lineMessagingClient.pushMessage(pushMessage).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }

    // Method untuk melakukan reply message
    private FlexMessage reply(ReplyMessage replyMessage) {
        try {
            lineMessagingClient.replyMessage(replyMessage).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
        return null;
    }


    // Method untuk mendapatkan identity2 pada suatu Line profiles
    private UserProfileResponse getProfile(String userId){
        try {
            return lineMessagingClient.getProfile(userId).get();
        } catch (InterruptedException | ExecutionException e) {
            throw new RuntimeException(e);
        }
    }


    private void replyText(String replyToken, String messageToUser){
        TextMessage textMessage = new TextMessage(messageToUser);
        ReplyMessage replyMessage = new ReplyMessage(replyToken, textMessage);
        reply(replyMessage);
    }


    // Method untuk menghasilkan Flex Message
    private FlexMessage replyFlexMessage1(String replyToken, String getKey) {
        try {
            ClassLoader classLoader = getClass().getClassLoader();
            String json = "";
            if (getKey.contains("walkot_detail")){
                json = "walkot_detail.json";
            } else if (getKey.contains("wawalkot_detail")){
                json = "wawalkot_detail.json";
            } else if (getKey.contains("summary_govern")){
                json = "carousel_govern.json";
            } else if (getKey.contains("summary_food")){
                json = "carousel_food.json";
            } else if (getKey.contains("summary_travel")){
                json = "carousel_travel.json";
            } else if (getKey.contains("summary_univ")){
                json = "carousel_univ.json";
            } else {
                json = "unknown.json";
            }
            String flexTemplate = IOUtils.toString(classLoader.getResourceAsStream(json));

            ObjectMapper objectMapper = ModelObjectMapper.createNewObjectMapper();
            FlexContainer flexContainer = objectMapper.readValue(flexTemplate, FlexContainer.class);

            ReplyMessage replyMessage = new ReplyMessage(replyToken, new FlexMessage("Flex Message", flexContainer));
            return reply(replyMessage);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }

    private FlexMessage replyFlexMessage2(String replyToken, String getKey) {
        try {
            ClassLoader classLoader = getClass().getClassLoader();
            String json = "";
            if (getKey.contains("walkot_detail")) {
                json = "walkot_detail.json";
            } else if (getKey.contains("wawalkot_detail")) {
                json = "wawalkot_detail.json";
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
                json = "unisba_details.json";
            } else {
                json = "unknown.json";
            }
            String flexTemplate = IOUtils.toString(classLoader.getResourceAsStream(json));

            ObjectMapper objectMapper = ModelObjectMapper.createNewObjectMapper();
            FlexContainer flexContainer = objectMapper.readValue(flexTemplate, FlexContainer.class);

            ReplyMessage replyMessage = new ReplyMessage(replyToken, new FlexMessage("Flex Message", flexContainer));
            return reply(replyMessage);
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

    public TemplateMessage createButton(String message, String actionTitle, String actionText) {
        ButtonsTemplate buttonsTemplate = new ButtonsTemplate(
                null,
                null,
                message,
                Collections.singletonList(new MessageAction(actionTitle, actionText))
        );

        return new TemplateMessage(actionTitle, buttonsTemplate);
    }

    public TemplateMessage greetingMessage(Source source) {
        String message  = "Hi %s! aku mungkin akan memandu kalian untuk mengetahui sebagian kecil tentang Babandungan :)";
        String label   = "Start";
        String action = "#init";

        if (source instanceof GroupSource) {
            message = String.format(message, "Group");
        } else if (source instanceof RoomSource) {
            message = String.format(message, "Room");
        } else if (source instanceof UserSource) {
            message = String.format(message, "User");
        } else {
            message = "Unknown Message Source!";
        }

        return createButton(message, label, action);
    }



}