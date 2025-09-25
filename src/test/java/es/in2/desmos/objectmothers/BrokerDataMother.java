package es.in2.desmos.objectmothers;

import com.fasterxml.jackson.databind.ObjectMapper;

public final class BrokerDataMother {

    public static final ObjectMapper objectMapper = new ObjectMapper();

    private BrokerDataMother() {
    }

    public static final String GET_ENTITY_REQUEST_BROKER_JSON =
            """
                    [
                        {
                            "id": "urn:productOffering:537e1ee3-0556-4fff-875f-e55bb97e7ab0",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a"
                            }
                        },
                        {
                            "id": "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7"
                            }
                        },
                        {
                            "id": "urn:productOffering:e8b7e5a7-5d0f-4c9b-b1e5-9b1af474207f",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:cf36a34a-4e43-453c-bf8b-4a926ed59a0c"
                            }
                        },
                        {
                            "id": "urn:productOffering:d1c34fc5-0c2b-4022-94ab-d7cb99d8edc2",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:ca9b5de4-bf5f-45de-8b33-0f2518f40e69"
                            }
                        },
                        {
                            "id": "urn:productOffering:39e31a28-583b-4f0d-80c6-6d7600cc9e36",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:faa692c0-1662-4fe2-b4e3-2d5ad86b47a1"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:cf36a34a-4e43-453c-bf8b-4a926ed59a0c",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:ab87d164-3a6c-4b61-9b40-6f615b96d35d"
                            },
                    		"priceAlteration": {
                                "type": "Relationship",
                                "object": "urn:priceAlteration:1bcaf091-16e3-4bbc-9800-a4636596384e"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:ca9b5de4-bf5f-45de-8b33-0f2518f40e69",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:21e7f562-f62d-41b7-8243-1241d0f871c2"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:faa692c0-1662-4fe2-b4e3-2d5ad86b47a1",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:5a1e08b4-eb32-4b68-af44-aa35e6a40fb9"
                            }
                        },
                        {
                            "id": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:ab87d164-3a6c-4b61-9b40-6f615b96d35d",
                            "type": "price"
                        },
                    	{
                            "id": "urn:priceAlteration:1bcaf091-16e3-4bbc-9800-a4636596384e",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:21e7f562-f62d-41b7-8243-1241d0f871c2",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:5a1e08b4-eb32-4b68-af44-aa35e6a40fb9",
                            "type": "price"
                        }
                    ]""";

    public static final String GET_ENTITY_REQUEST_WITH_SUB_ENTITIES_ARRAY_JSON =
            """
                    [
                        {
                            "id": "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27",
                            "type": "productOffering",
                            "category": [
                                 {
                                     "type": "Relationship",
                                     "object": "urn:category:ebdaf266-c967-43bc-a469-c6b6498facff",
                                     "datasetId": "urn:dataset:category:1"
                                 },
                                 {
                                     "type": "Relationship",
                                     "object": "urn:category:363a79ba-38ed-48c4-978c-131521b943ef",
                                     "datasetId": "urn:dataset:category:2"
                                 }
                            ]
                        },
                        {
                            "id": "urn:category:ebdaf266-c967-43bc-a469-c6b6498facff",
                            "type": "category"
                        },
                        {
                            "id": "urn:category:363a79ba-38ed-48c4-978c-131521b943ef",
                            "type": "category"
                        }
                    ]""";

    public static final String GET_ENTITY_REQUEST_ENTITY_ID =
            "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27";
    public static final String GET_ENTITY_REQUEST_SUBENTITY_1_ID =
            "urn:category:ebdaf266-c967-43bc-a469-c6b6498facff";
    public static final String GET_ENTITY_REQUEST_SUBENTITY_2_ID =
            "urn:category:363a79ba-38ed-48c4-978c-131521b943ef";

    public static final String GET_ENTITY_REQUEST_ENTITY_ID_2 =
            "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d28";
    public static final String GET_ENTITY_REQUEST_SUBENTITY_1_ID_2 =
            "urn:category:ebdaf266-c967-43bc-a469-c6b6498facfz";
    public static final String GET_ENTITY_REQUEST_SUBENTITY_2_ID_2 =
            "urn:category:363a79ba-38ed-48c4-978c-131521b943ej";

    public static final String GET_ENTITY_REQUEST_WITH_SUB_ENTITIES_ARRAY_JSON_VARIABLE = String.format("""
            [
                {
                    "id": "%1$s",
                    "type": "productOffering",
                    "category": [
                         {
                             "type": "Relationship",
                             "object": "%2$s",
                             "datasetId": "urn:dataset:category:1"
                         },
                         {
                             "type": "Relationship",
                             "object": "%3$s",
                             "datasetId": "urn:dataset:category:2"
                         }
                    ]
                },
                {
                    "id": "%2$s",
                    "type": "category"
                },
                {
                    "id": "%3$s",
                    "type": "category"
                }
            ]""", GET_ENTITY_REQUEST_ENTITY_ID_2, GET_ENTITY_REQUEST_SUBENTITY_1_ID_2, GET_ENTITY_REQUEST_SUBENTITY_2_ID_2);

    public static final String GET_ENTITY_REQUEST_WITH_SUB_ENTITIES_ARRAY_WITH_PROPERTY_JSON =
            """
                    [
                        {
                            "id": "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27",
                            "type": "productOffering",
                            "category": {
                                 "type": "Property",
                                 "value": [
                                     {
                                         "type": "Relationship",
                                         "object": "urn:category:ebdaf266-c967-43bc-a469-c6b6498facff"
                                     },
                                     {
                                         "type": "Relationship",
                                         "object": "urn:category:363a79ba-38ed-48c4-978c-131521b943ef"
                                     }
                                 ]
                            }
                        },
                        {
                            "id": "urn:category:ebdaf266-c967-43bc-a469-c6b6498facff",
                            "type": "category"
                        },
                        {
                            "id": "urn:category:363a79ba-38ed-48c4-978c-131521b943ef",
                            "type": "category"
                        }
                    ]""";

    public static final String GET_ENTITY_REQUEST_BROKER_NO_TYPE_JSON =
            """
                    [
                        {
                            "id": "urn:productOffering:537e1ee3-0556-4fff-875f-e55bb97e7ab0",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "hola": "Relationship",
                                "object": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a"
                            }
                        },
                        {
                            "id": "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf"
                            }
                        },
                        {
                            "id": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf",
                            "type": "price"
                        }
                    ]""";

    public static final String GET_ENTITY_REQUEST_BROKER_NO_RELATIONSHIP_JSON =
            """
                    [
                        {
                            "id": "urn:productOffering:537e1ee3-0556-4fff-875f-e55bb97e7ab0",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "hola",
                                "object": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a"
                            }
                        },
                        {
                            "id": "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf"
                            }
                        },
                        {
                            "id": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf",
                            "type": "price"
                        }
                    ]""";

    public static final String GET_ENTITY_REQUEST_BROKER_NO_OBJECT_JSON =
            """
                    [
                        {
                            "id": "urn:productOffering:537e1ee3-0556-4fff-875f-e55bb97e7ab0",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "hola": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a"
                            }
                        },
                        {
                            "id": "urn:productOffering:06f56a54-9be9-4d45-bae7-2a036b721d27",
                            "type": "productOffering",
                            "productOfferingPrice": {
                                "type": "Relationship",
                                "object": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:912efae1-7ff6-4838-89f3-cfedfdfa1c5a",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b"
                            }
                        },
                        {
                            "id": "urn:productOfferingPrice:a395344e-2c29-4d36-8463-0c0412f024d7",
                            "type": "productOfferingPrice",
                            "price": {
                                "type": "Relationship",
                                "object": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf"
                            }
                        },
                        {
                            "id": "urn:price:2d5f3c16-4e77-45b3-8915-3da36b714e7b",
                            "type": "price"
                        },
                        {
                            "id": "urn:price:6380d7c9-d9ec-4d35-865b-76e72d081cbf",
                            "type": "price"
                        }
                    ]""";

    public static final String GET_REAL_PRODUCT_OFFERING = """
            {
                 "id": "urn:ngsi-ld:product-offering:421b4810-b015-4a02-b6ed-e9386d10dc99",
                 "type": "product-offering",
                 "description": {
                     "type": "Property",
                     "value": "this is my offer"
                 },
                 "href": {
                     "type": "Property",
                     "value": "urn:ngsi-ld:product-offering:421b4810-b015-4a02-b6ed-e9386d10dc99"
                 },
                 "isBundle": {
                     "type": "Property",
                     "value": false
                 },
                 "lastUpdate": {
                     "type": "Property",
                     "value": "2024-05-02T08:30:27.216619051Z"
                 },
                 "lifecycleStatus": {
                     "type": "Property",
                     "value": "LAUNCHED"
                 },
                 "name": {
                     "type": "Property",
                     "value": "Offer"
                 },
                 "productOfferingPrice": {
                     "type": "Relationship",
                     "href": {
                         "type": "Property",
                         "value": "urn:ngsi-ld:product-offering-price:99645d45-07e3-4ed1-ad7f-897939ad8455"
                     },
                     "object": "urn:ngsi-ld:product-offering-price:99645d45-07e3-4ed1-ad7f-897939ad8455"
                 },
                 "productOfferingTerm": {
                     "type": "Property",
                     "value": {
                         "description": "terms and conditions",
                         "name": "terms",
                         "validFor": {
                         }
                     }
                 },
                 "productSpecification": {
                     "type": "Relationship",
                     "href": {
                         "type": "Property",
                         "value": "urn:ngsi-ld:product-specification:a1f3bc96-8ab4-4532-be2b-64629f13eaa1"
                     },
                     "object": "urn:ngsi-ld:product-specification:a1f3bc96-8ab4-4532-be2b-64629f13eaa1"
                 },
                 "validFor": {
                     "type": "Property",
                     "value": {
                         "startDateTime": "2024-06-02T08:30:27.034Z"
                     }
                 },
                 "version": {
                     "type": "Property",
                     "value": "0.1"
                 },
                 "@context": [
                     "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context-v1.7.jsonld"
                 ]
             }
            """;

    public static final String GET_REAL_CATALOG = """
            {
                "id": "urn:ngsi-ld:catalog:eda58597-fef2-44f3-bec3-3e2dc89fe051",
                "type": "catalog",
                "description": {
                    "type": "Property",
                    "value": "new catalog"
                },
                "href": {
                    "type": "Property",
                    "value": "urn:ngsi-ld:catalog:eda58597-fef2-44f3-bec3-3e2dc89fe051"
                },
                "lifecycleStatus": {
                    "type": "Property",
                    "value": "Launched"
                },
                "name": {
                    "type": "Property",
                    "value": "new"
                },
                "relatedParty": {
                    "type": "Relationship",
                    "@referredType": {
                        "type": "Property",
                        "value": ""
                    },
                    "role": {
                        "type": "Property",
                        "value": "Owner"
                    },
                    "object": "urn:ngsi-ld:organization:38063c78-fc9f-42ca-a39e-518107a2d412"
                },
                "validFor": {
                    "type": "Property",
                    "value": {
                        "startDateTime": "2024-04-26T09:29:20.162Z"
                    }
                },
                "@context": [
                    "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context-v1.7.jsonld"
                ]
            }""";

    public static final String GET_REAL_CATEGORY = """
            {
                     "id": "urn:ngsi-ld:category:9844c35f-23cc-46a6-9a4d-2ac52071b80c",
                     "type": "category",
                     "href": {
                         "type": "Property",
                         "value": "urn:ngsi-ld:category:9844c35f-23cc-46a6-9a4d-2ac52071b80c"
                     },
                     "isRoot": {
                         "type": "Property",
                         "value": false
                     },
                     "lastUpdate": {
                         "type": "Property",
                         "value": "2024-09-02T10:26:16.775184Z"
                     },
                     "lifecycleStatus": {
                         "type": "Property",
                         "value": "Launched"
                     },
                     "name": {
                         "type": "Property",
                         "value": "Storage"
                     },
                     "parentId": {
                         "type": "Relationship",
                         "object": "urn:ngsi-ld:category:dc467e33-a275-43fa-9a71-c45eb8cfc38d"
                     },
                     "validFor": {
                         "type": "Property",
                         "value": {
                             "startDateTime": "2024-03-02T10:26:16.734Z"
                         }
                     },
                     "@context": [
                         "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context-v1.7.jsonld"
                     ]
                 }""";

    public static final String NOT_ROOT_OBJECT = """
            {
                     "id": "urn:ngsi-ld:other:4944c35f-23cc-46a6-9a4d-2ac52071b832",
                     "type": "other",
                     "href": {
                         "type": "Property",
                         "value": "urn:ngsi-ld:other:4944c35f-23cc-46a6-9a4d-2ac52071b832"
                     },
                     "isRoot": {
                         "type": "Property",
                         "value": false
                     },
                     "lastUpdate": {
                         "type": "Property",
                         "value": "2024-09-02T10:26:16.775184Z"
                     },
                     "lifecycleStatus": {
                         "type": "Property",
                         "value": "Launched"
                     },
                     "name": {
                         "type": "Property",
                         "value": "Storage"
                     },
                     "validFor": {
                         "type": "Property",
                         "value": {
                             "startDateTime": "2024-03-02T10:26:16.734Z"
                         }
                     },
                     "@context": [
                         "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context-v1.7.jsonld"
                     ]
                 }""";

    public static final String WITHOUT_ID = """
            {
                 "type": "other",
                 "@context": [
                     "https://uri.etsi.org/ngsi-ld/v1/ngsi-ld-core-context-v1.7.jsonld"
                 ]
             }""";
}
