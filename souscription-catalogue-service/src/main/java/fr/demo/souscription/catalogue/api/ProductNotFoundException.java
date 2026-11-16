package fr.demo.souscription.catalogue.api;

public class ProductNotFoundException extends RuntimeException {

    private final String code;

    public ProductNotFoundException(String code) {
        super("Produit inconnu : " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
