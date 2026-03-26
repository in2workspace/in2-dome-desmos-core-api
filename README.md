<div align="center">
<h1>IN2 Desmos API</h1>
<span>by </span><a href="https://in2.es">in2.es</a>
<p><p>

[![Quality Gate Status](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=alert_status)](https://sonarcloud.io/dashboard?id=in2workspace_in2-desmos-api)

[![Bugs](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=bugs)](https://sonarcloud.io/summary/new_code?id=in2workspace_in2-desmos-api)
[![Vulnerabilities](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=vulnerabilities)](https://sonarcloud.io/dashboard?id=in2workspace_in2-desmos-api)
[![Security Rating](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=security_rating)](https://sonarcloud.io/dashboard?id=in2workspace_in2-desmos-api)
[![Code Smells](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=code_smells)](https://sonarcloud.io/summary/new_code?id=in2workspace_in2-desmos-api)
[![Lines of Code](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=ncloc)](https://sonarcloud.io/dashboard?id=in2workspace_in2-desmos-api)

[![Coverage](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=coverage)](https://sonarcloud.io/summary/new_code?id=in2workspace_in2-desmos-api)
[![Duplicated Lines (%)](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=duplicated_lines_density)](https://sonarcloud.io/summary/new_code?id=in2workspace_in2-desmos-api)
[![Reliability Rating](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=reliability_rating)](https://sonarcloud.io/dashboard?id=in2workspace_in2-desmos-api)
[![Maintainability Rating](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=sqale_rating)](https://sonarcloud.io/dashboard?id=in2workspace_in2-desmos-api)
[![Technical Debt](https://sonarcloud.io/api/project_badges/measure?project=in2workspace_in2-desmos-api&metric=sqale_index)](https://sonarcloud.io/summary/new_code?id=in2workspace_in2-desmos-api)

</div>

# Introduction
Desmos API is a Blockchain Connector solution developed by IN2.
As a Blockchain Connector, it is a crucial part within the Access Node architecture
facilitating interaction between the Off-Chain Storage and the On-Chain Storage. 
This RESTFul API exposes a set of endpoints designed for seamless communication between a Context Broker and a Blockchain Adapter.


# Installation

We offer a Docker image to run the application. You can find it in [Docker Hub](https://hub.docker.com/repository/docker/in2kizuna/desmos/general).

Here, you can find the docker-compose.yml file to run the application with all the required dependencies (Context Broker and Blockchain Adapter).

## Running the application (the easy way)
```bash
cd config/docker
docker-compose up -d
```

## Understanding the Configuration

## Documentation

- [Application Flow](docs/APPLICATION_FLOW.md) - Detailed documentation about the application architecture and workflows
- [Troubleshooting Guide](docs/TROUBLESHOOTING.md) - Solutions for common issues and problems

## Contribution

### How to contribute
If you want to contribute to this project, please read the [CONTRIBUTING.md](CONTRIBUTING.md) file.

## License
This project is licensed under the Apache License 2.0 — see the [LICENSE](LICENSE) file for details.

## Project/Component Status
This project is currently in development.

## Contact
For any inquiries or further information, feel free to reach out to us:

- **Email:** [Oriol Canadés](mailto:oriol.canades@in2.es)
- **Name:** IN2, Ingeniería de la Información
- **Website:** [https://in2.es](https://in2.es)

## Acknowledgments
This project is part of the IN2 strategic R&D, which has received funding from the [DOME](https://dome-marketplace.eu/) project within the European Union’s Horizon Europe Research and Innovation program under the Grant Agreement No. 101084071.
