package de.othr.sw.quickstart.service;

import de.othr.sw.quickstart.dtos.*;
import de.othr.sw.quickstart.entity.*;

import de.othr.sw.quickstart.helpclassAndConfig.M26Config;
import de.othr.sw.quickstart.helpclassAndConfig.YAMLConfig;
import de.othr.sw.quickstart.remoteRequest.RemoteSchufaHandlerIF;
import de.othr.sw.quickstart.repository.AccountRepository;
import de.othr.sw.quickstart.repository.CreditRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Scope;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.Optional;

@Service
@Scope("singleton")
public class CreditService implements CreditServiceIF{
    @Autowired
    YAMLConfig yamlConfig;
    @Autowired
    @Qualifier("TransferHandlerCredit")
    private TransferHandlerIF transferHandlerCredit;
    @Autowired
    @Qualifier("TransferHandlerCustomer")
    private TransferHandlerIF transferHandlerCustomer;
    @Autowired
    private AccountRepository accountRepository;
    @Autowired
    private CreditRepository creditRepository;
    @Autowired
    RemoteSchufaHandlerIF remoteSchufaHandler;

    @Override
    public Risikostufe getRisikoStufe(String iban, long amount) {
        //return embargo if no account
        if (accountRepository.findByIban(iban).isEmpty()) {
            return Risikostufe.EMBARGO;
        }
        Customer customer = accountRepository.findByIban(iban).get().getAccountHolder();
        //get risk estimation
        Optional<RiskResponseDto> riskResponseDtoO = remoteSchufaHandler.getRiskEstimation(customer, amount);

        //return embargo if schufa couldnt be reached as standart value
        if(riskResponseDtoO.isEmpty())
            return Risikostufe.EMBARGO;

        Risikostufe risikostufe = riskResponseDtoO.get().getRisikostufe();
        return risikostufe;
    }

    @Override
    @Transactional(propagation = Propagation.REQUIRED)
    public boolean requestCredit(String receiverIban, long amount) {
        if (accountRepository.findByIban(receiverIban).isEmpty()) {
            return false;
        }

        String name = accountRepository.findByIban(receiverIban).get().getAccountHolder().getFirstName() + "" + accountRepository.findByIban(receiverIban).get().getAccountHolder().getLastName();
        Date birthday = accountRepository.findByIban(receiverIban).get().getAccountHolder().getBirthday();

        //get accounts from bank
        List<Account> bAccounts = accountRepository.findByAccountHolder_Username(yamlConfig.getBankName());

        //iterate over bAccounts if bank has more than one account
        for (Account a: bAccounts
             ) {
            //call credit handler
            Optional<Transaction> transactionO = transferHandlerCredit.transferMoney(a.getIban(), receiverIban, amount);
            if(transactionO.isPresent()) {
                if (transactionO.get().getStatus() == TransactionStatus.SUCCESS){
                    //notify schufa
                    remoteSchufaHandler.updateUser("kreditaufnahme", Art.KREDITAUFGENOMMEN, (int)amount, name, birthday);
                    return true;
                } else {
                    return false;
                }
            } else {
                return false;
            }
        }
        return false;
    }

    @Scheduled(cron = M26Config.creditRepayRate)
    public void scheduledRepay() {
        //get all acounts
        Iterable<Account> accountsIterable = accountRepository.findAll();
        for (Account a : accountsIterable
             ) {
            //skip bank account
            if (!(a.getAccountHolder().getUsername().equals(yamlConfig.getBankName()))){
                repayCreditRate(a);
            } else {
            }
        }
    }

    @Transactional(propagation = Propagation.REQUIRED)
    public void repayCreditRate(Account a) {
        //send money to first account of bank m26
        String bankIban = accountRepository.findByAccountHolder_Username(yamlConfig.getBankName()).get(0).getIban();

        //if account is account from bank return
        //get credits
        List<Credit> credits = a.getCredits();
        for (Credit c: credits
             ) {
            if(c.isActiveCredit()) {
                long repaymentRate = c.getRepaymentRate();
                //check if repayment rate is higher than remaining amount -> if so then adjust repaymentRate
                if((c.getRemainingAmountBack() - repaymentRate) <= 0) {
                    repaymentRate = c.getRemainingAmountBack();
                }
                //do transaction
                Optional<Transaction> transO = transferHandlerCustomer.transferMoney(a.getIban(), bankIban, repaymentRate);
                //check if worked
                if(transO.isEmpty())
                    return;
                Transaction transaction = transO.get();
                if(transaction.getStatus() == TransactionStatus.SUCCESS) {
                    //repayment worked
                    if((c.getRemainingAmountBack() - repaymentRate) > 0) {
                        c.setRemainingAmountBack(c.getRemainingAmountBack() - repaymentRate);
                    } else {
                        //repaid credit
                        //notify schufa
                        String name = accountRepository.findByIban(a.getIban()).get().getAccountHolder().getFirstName() + "" + accountRepository.findByIban(a.getIban()).get().getAccountHolder().getLastName();
                        remoteSchufaHandler.updateUser("kreditabbezahlt", Art.KREDITABBEZAHLT, (int)c.getAmount(), a.getAccountHolder().getFirstName() + " " + a.getAccountHolder().getLastName(), a.getAccountHolder().getBirthday());

                        c.setRemainingAmountBack(0);
                        c.setActiveCredit(false);
                    }
                    creditRepository.save(c);
                } else {
                    //repayment didnt work
                    /// punitive interest (customer cant pay -> customer has to pay remaining credit * standard interest rate)
                    c.setRemainingAmountBack(c.getRemainingAmountBack() + Math.round(((double)c.getRemainingAmountBack() * ((double)yamlConfig.getStandardInterestRate() / 1000))));
                    creditRepository.save(c);
                }
            }
        }
    }
}
