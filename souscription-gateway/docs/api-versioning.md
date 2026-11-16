# Versionnement de l'API publique — souscription-gateway

## Version majeure publique : `/api/v1`

Toutes les ressources publiques du gateway sont servies sous le prefixe `/api/v1` (OD-008).
Le `v1` du chemin est la **version majeure** du contrat publie dans
`global/contracts/souscription-web-to-souscription-gateway/openapi.yaml` et expose par
`GET /q/openapi`.

Les chemins `/v1` des services aval (souscription-catalogue-service,
souscription-simulation-service, souscription-service) sont **internes** : ils ne sont pas
joignables depuis l'exterieur du cluster et leur versionnement est independant de celui de
l'API publique. Le gateway est le seul point de couplage entre les deux.

Les chemins `*.do` de l'application Struts d'origine ne sont pas un contrat preserve : aucun
alias n'est servi.

## Compatibilite a l'interieur d'une version majeure

Sont **autorises** sans changement de version (additifs uniquement) :

- ajout d'un nouveau champ **optionnel** dans une reponse ;
- ajout d'un nouveau champ **optionnel** dans un corps de requete ;
- ajout d'une nouvelle operation ou d'un nouveau chemin ;
- ajout d'un code de reponse d'erreur documente ;
- enrichissement d'une description, d'un `summary` ou d'un exemple.

Sont **interdits** sans changement de version majeure :

- suppression ou renommage d'un champ, d'un chemin ou d'un `operationId` ;
- passage d'un champ d'optionnel a obligatoire ;
- changement de type, de format ou d'echelle (les montants sont des chaines JSON pour
  preserver les echelles `DECIMAL(14,2)` et le taux `DECIMAL(8,6)`) ;
- changement du code de statut d'une reponse existante ;
- ajout d'une pagination sur une collection existante (`GET /api/v1/products`).

## Changement incompatible

Un changement incompatible donne lieu a un `/api/v2` **additif**, servi en parallele de
`/api/v1`. La version `v1` n'est jamais modifiee en place. La fin de vie de `v1` est annoncee
separement et suppose que souscription-web ait migre ses modeles TypeScript generes.

## Source de verite

En cas de divergence entre le document genere par les annotations et le fichier
`global/contracts/souscription-web-to-souscription-gateway/openapi.yaml`, **le fichier
committe gagne** : la divergence est une rupture de contrat et doit etre corrigee dans le code.
C'est ce que verifie `OpenApiContractTest`.
