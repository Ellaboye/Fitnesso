package com.decagon.fitnessoapp.service.serviceImplementation;

import com.decagon.fitnessoapp.Email.EmailService;
import com.decagon.fitnessoapp.config.cloudinary.CloudinaryConfig;
import com.decagon.fitnessoapp.dto.*;
import com.decagon.fitnessoapp.exception.CustomServiceExceptions;
import com.decagon.fitnessoapp.exception.PersonNotFoundException;
import com.decagon.fitnessoapp.model.user.Person;
import com.decagon.fitnessoapp.model.user.ROLE_DETAIL;
import com.decagon.fitnessoapp.model.user.Role;
import com.decagon.fitnessoapp.repository.PersonRepository;
import com.decagon.fitnessoapp.security.JwtUtils;
import com.decagon.fitnessoapp.service.PersonService;
import com.mailjet.client.errors.MailjetException;
import com.mailjet.client.errors.MailjetSocketTimeoutException;
import lombok.extern.slf4j.Slf4j;
import net.bytebuddy.utility.RandomString;
import org.modelmapper.ModelMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
public class PersonServiceImpl implements PersonService {

    private final VerificationTokenServiceImpl verificationTokenService;
    private final PasswordEncoder bCryptPasswordEncoder;
    private final PersonRepository personRepository;
    private final EmailValidator emailValidator;
    private final ModelMapper modelMapper;
    private final EmailService emailSender;
    private final AuthenticationManager authenticationManager;
    private final JwtUtils jwtUtils;
    private final PersonDetailsService userDetailsService;
    @Value("${website.address}")
    private String website;
    @Value("${server.port}")
    private Integer port;


    @Autowired
    public PersonServiceImpl(VerificationTokenServiceImpl verificationTokenService, PasswordEncoder bCryptPasswordEncoder,
                             PersonRepository personRepository, EmailValidator emailValidator, ModelMapper modelMapper,
                             EmailService emailSender, JwtUtils jwtUtils,
                             PersonDetailsService userDetailsService
            , AuthenticationManager authenticationManager) {
        this.verificationTokenService = verificationTokenService;
        this.bCryptPasswordEncoder = bCryptPasswordEncoder;
        this.personRepository = personRepository;
        this.emailValidator = emailValidator;
        this.modelMapper = modelMapper;
        this.emailSender = emailSender;
        this.authenticationManager = authenticationManager;
        this.jwtUtils = jwtUtils;
        this.userDetailsService = userDetailsService;
    }

    @Override
    public PersonResponse register(PersonRequest personRequest) throws MailjetSocketTimeoutException, MailjetException, IOException {
        boolean isValidEmail = emailValidator.test(personRequest.getEmail());
        if(!isValidEmail){
            throw new CustomServiceExceptions("Not a valid email");
        }

        boolean isValidNumber = emailValidator.validatePhoneNumber(personRequest.getPhoneNumber());

        if(!isValidNumber){
            throw new CustomServiceExceptions("Not a valid phone number");
        }

        boolean userExists = personRepository.findByEmail(personRequest.getEmail()).isPresent();
        if(userExists){
            throw  new CustomServiceExceptions("email taken");
        }

        Person person = new Person();
        modelMapper.map(personRequest, person);


        final String encodedPassword = bCryptPasswordEncoder.encode(personRequest.getPassword());
        person.setPassword(encodedPassword);
        String token = RandomString.make(64);
        person.setResetPasswordToken(token);
//        CloudinaryConfig cloudinaryConfig = new CloudinaryConfig();
//        String url = cloudinaryConfig.createImage(person.getImage());
//        person.setImage(url);
        personRepository.save(person);
        sendingEmail(personRequest);

        PersonResponse personResponse = new PersonResponse();
        modelMapper.map(person, personResponse);
        return personResponse;
    }

    @Override
    public PersonResponse addTrainer(PersonRequest personRequest){
        Person person = personRepository.findByEmail(personRequest.getEmail())
                .orElseThrow(()-> new PersonNotFoundException("Email does not exist"));

        if(person.getEmail() != null &&  person.getRoleDetail() != ROLE_DETAIL.TRAINER && person.getRoleDetail() != ROLE_DETAIL.ADMIN){
            person.setRoleDetail(ROLE_DETAIL.TRAINER);
        }
        personRepository.save(person);

        return modelMapper.map(person, PersonResponse.class);

    }

    @Override
    public ResponseEntity<String> removeTrainer(Long id) {
        Person person = personRepository.getById(id);
        if (person.getRoleDetail() == ROLE_DETAIL.TRAINER) {
            personRepository.delete(person);
            return ResponseEntity.ok("Response: Trainer deleted successfully");
        }
         return ResponseEntity.ok("Response: User not a Trainer");
    }

    @Override
    public void sendingEmail(PersonRequest personRequest) throws MailjetSocketTimeoutException, MailjetException {
        Person person = personRepository.findByEmail(personRequest.getEmail())
                .orElseThrow(() -> new CustomServiceExceptions("Email not registered"));
        String token = verificationTokenService.saveVerificationToken(person);
        String link = "http://"+ website + ":" + port + "/person/confirm?token=" + token;
        String subject = "Confirm your email";
        emailSender.sendMessage(subject, person.getEmail(), buildEmail(person.getFirstName(), link));
    }



    @Override
    public ResponseEntity<AuthResponse> loginUser(AuthRequest req) throws Exception {
        try {
            Authentication authentication = authenticationManager.authenticate(new UsernamePasswordAuthenticationToken(req.getUsername(),
                    req.getPassword()));
            SecurityContextHolder.getContext().setAuthentication(authentication);
            final PersonDetails person = userDetailsService.loadUserByUsername(req.getUsername());
            List<String> roles = person.getAuthorities().stream().map(GrantedAuthority::getAuthority).collect(Collectors.toList());
            log.info("{} {}", roles.size(),roles);

            final String jwt = jwtUtils.generateToken(person);
            final AuthResponse res = new AuthResponse();

            String role =null;
            for (String r : roles) {
                if (r!=null) role = r;
            }
            res.setToken(jwt);
            res.setRole(role);
            return ResponseEntity.ok().body(res);
        } catch (Exception e) {
            throw new Exception("incorrect username or password!", e);
        }
    }

    @Override
    public UpdatePersonResponse updateUserDetails(UpdatePersonRequest updatePersonRequest) {
        Person existingPerson = personRepository.findPersonByUserName(updatePersonRequest.getUserName())
                .orElseThrow(
                        () -> new PersonNotFoundException("Person Not Found")
                );
        modelMapper.map(updatePersonRequest, existingPerson);
        personRepository.save(existingPerson);
        UpdatePersonResponse response = new UpdatePersonResponse();
        modelMapper.map(existingPerson,response);
        return response;
    }

    @Override
    @Transactional
    public ChangePasswordResponse updateCurrentPassword(ChangePasswordRequest changePasswordRequest) {
        Person currentPerson = personRepository.findByUserName(changePasswordRequest.getUserName())
                .orElseThrow(()-> new PersonNotFoundException("Person Not Found"));
        String newPassword = changePasswordRequest.getNewPassword();
        String confirmPassword = changePasswordRequest.getConfirmPassword();
        if(bCryptPasswordEncoder.matches(changePasswordRequest.getCurrentPassword(), currentPerson.getPassword())){
            if (newPassword.equals(confirmPassword)) {
                currentPerson.setPassword(bCryptPasswordEncoder.encode(newPassword));
                personRepository.save(currentPerson);
                return new ChangePasswordResponse("password successfully changed");
            }
            else { return new ChangePasswordResponse("password mix match");}
        }
        else {
            return new ChangePasswordResponse("Incorrect current password");
        }
    }

    @Override
    public String resetPasswordToken(String email) throws MailjetSocketTimeoutException, MailjetException {
        Person person = personRepository.findByEmail(email)
                .orElseThrow(()-> new PersonNotFoundException("Email not Registered"));
        String token = RandomString.make(64);
        //TODO:remove after testing app
        System.out.println(token);
        person.setResetPasswordToken(token);
        personRepository.save(person);
        resetPasswordMailSender(person.getEmail(), token);
        return "email sent";
    }


    @Override
    public String updateResetPassword(ResetPasswordRequest passwordRequest, String token) {
        Person person = personRepository.findByResetPasswordToken(token)
                .orElseThrow(()-> new PersonNotFoundException("Person not found"));
        if(passwordRequest.getNewPassword().equals(passwordRequest.getConfirmPassword())){
            person.setPassword(bCryptPasswordEncoder.encode(passwordRequest.getNewPassword()));
            personRepository.save(person);
            return "updated";
        }
        return "mismatch of new and confirm password";
    }

    @Override
    public void resetPasswordMailSender(String email, String token) throws MailjetSocketTimeoutException,
            MailjetException {
        String resetPasswordLink = "http://"+ website + ":" + port + "/update_password?token=" + token;
        String subject = "Here's the link to reset your password";
        String content = "<p>Hello,</p>"
                + "<p>You have requested to reset your password.</p>"
                + "<p>Click the link below to change your password:</p>"
                + "<p><a href=\"" + resetPasswordLink + "\">Change my password</a></p>"
                + "<br>"
                + "<p> Ignore this email if you do remember your password, "
                + "or you have not made the request.</p>";
        emailSender.sendMessage(subject, email, content);
    }

    @Override
    public String buildEmail(String name, String link) {
        return "<div style=\"font-family:Helvetica,Arial,sans-serif;font-size:16px;margin:0;color:#0b0c0c\">\n" +
                "\n" +
                "<span style=\"display:none;font-size:1px;color:#fff;max-height:0\"></span>\n" +
                "\n" +
                "  <table role=\"presentation\" width=\"100%\" style=\"border-collapse:collapse;min-width:100%;width:100%!important\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\">\n" +
                "    <tbody><tr>\n" +
                "      <td width=\"100%\" height=\"53\" bgcolor=\"#0b0c0c\">\n" +
                "        \n" +
                "        <table role=\"presentation\" width=\"100%\" style=\"border-collapse:collapse;max-width:580px\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" align=\"center\">\n" +
                "          <tbody><tr>\n" +
                "            <td width=\"70\" bgcolor=\"#0b0c0c\" valign=\"middle\">\n" +
                "                <table role=\"presentation\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse\">\n" +
                "                  <tbody><tr>\n" +
                "                    <td style=\"padding-left:10px\">\n" +
                "                  \n" +
                "                    </td>\n" +
                "                    <td style=\"font-size:28px;line-height:1.315789474;Margin-top:4px;padding-left:10px\">\n" +
                "                      <span style=\"font-family:Helvetica,Arial,sans-serif;font-weight:700;color:#ffffff;text-decoration:none;vertical-align:top;display:inline-block\">Confirm your email</span>\n" +
                "                    </td>\n" +
                "                  </tr>\n" +
                "                </tbody></table>\n" +
                "              </a>\n" +
                "            </td>\n" +
                "          </tr>\n" +
                "        </tbody></table>\n" +
                "        \n" +
                "      </td>\n" +
                "    </tr>\n" +
                "  </tbody></table>\n" +
                "  <table role=\"presentation\" class=\"m_-6186904992287805515content\" align=\"center\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse;max-width:580px;width:100%!important\" width=\"100%\">\n" +
                "    <tbody><tr>\n" +
                "      <td width=\"10\" height=\"10\" valign=\"middle\"></td>\n" +
                "      <td>\n" +
                "        \n" +
                "                <table role=\"presentation\" width=\"100%\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse\">\n" +
                "                  <tbody><tr>\n" +
                "                    <td bgcolor=\"#1D70B8\" width=\"100%\" height=\"10\"></td>\n" +
                "                  </tr>\n" +
                "                </tbody></table>\n" +
                "        \n" +
                "      </td>\n" +
                "      <td width=\"10\" valign=\"middle\" height=\"10\"></td>\n" +
                "    </tr>\n" +
                "  </tbody></table>\n" +
                "\n" +
                "\n" +
                "\n" +
                "  <table role=\"presentation\" class=\"m_-6186904992287805515content\" align=\"center\" cellpadding=\"0\" cellspacing=\"0\" border=\"0\" style=\"border-collapse:collapse;max-width:580px;width:100%!important\" width=\"100%\">\n" +
                "    <tbody><tr>\n" +
                "      <td height=\"30\"><br></td>\n" +
                "    </tr>\n" +
                "    <tr>\n" +
                "      <td width=\"10\" valign=\"middle\"><br></td>\n" +
                "      <td style=\"font-family:Helvetica,Arial,sans-serif;font-size:19px;line-height:1.315789474;max-width:560px\">\n" +
                "        \n" +
                "            <p style=\"Margin:0 0 20px 0;font-size:19px;line-height:25px;color:#0b0c0c\">Hi " + name + ",</p><p style=\"Margin:0 0 20px 0;font-size:19px;line-height:25px;color:#0b0c0c\"> Thank you for registering. Please click on the below link to activate your account: </p><blockquote style=\"Margin:0 0 20px 0;border-left:10px solid #b1b4b6;padding:15px 0 0.1px 15px;font-size:19px;line-height:25px\"><p style=\"Margin:0 0 20px 0;font-size:19px;line-height:25px;color:#0b0c0c\"> <a href=\"" + link + "\">Activate Now</a> </p></blockquote>\n Link will expire in 24 hours. <p>See you soon</p>" +
                "        \n" +
                "      </td>\n" +
                "      <td width=\"10\" valign=\"middle\"><br></td>\n" +
                "    </tr>\n" +
                "    <tr>\n" +
                "      <td height=\"30\"><br></td>\n" +
                "    </tr>\n" +
                "  </tbody></table><div class=\"yj6qo\"></div><div class=\"adL\">\n" +
                "\n" +
                "</div></div>";
    }
}
