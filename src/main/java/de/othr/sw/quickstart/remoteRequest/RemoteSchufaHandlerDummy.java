package de.othr.sw.quickstart.remoteRequest;

import de.othr.sw.quickstart.dtos.Art;
import de.othr.sw.quickstart.dtos.Risikostufe;
import de.othr.sw.quickstart.dtos.RiskResponseDto;
import de.othr.sw.quickstart.entity.Customer;

import java.util.Random;

public class RemoteSchufaHandlerDummy implements RemoteSchufaHandlerIF {
    @Override
    public RiskResponseDto getRiskEstimation(Customer customer, long amount) {
        RiskResponseDto riskResponseDto = new RiskResponseDto();
        //random risikostufe
        Risikostufe randomRisikoStufe = Risikostufe.values()[new Random().nextInt(Risikostufe.values().length)];
        riskResponseDto.setRisikostufe(randomRisikoStufe);
        return riskResponseDto;
    }

    @Override
    public boolean updateUser(String name, Art art, int betrag) {
        return true;
    }
}