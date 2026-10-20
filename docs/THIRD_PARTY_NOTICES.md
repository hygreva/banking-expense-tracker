# Third party materials

AWS service icons are included solely to explain the deployment architecture. The selected PNG assets came from https://github.com/mingrammer/diagrams/tree/master/resources/aws and reflect that library's AWS icon set, not an assertion that it is the latest AWS quarterly release. The diagrams project is distributed under the MIT license; see https://github.com/mingrammer/diagrams/blob/master/LICENSE. AWS service names and marks belong to Amazon Web Services. Use of AWS architecture icons is described at https://aws.amazon.com/architecture/icons/.

The bundled RDS CA certificates come from https://truststore.pki.rds.amazonaws.com/global/global-bundle.pem. They are public trust certificates, not secrets. Review and refresh the trust bundle when AWS changes the required trust roots.

Application dependencies are declared in backend/pom.xml and frontend/package.json, with frontend versions resolved in package-lock.json. Review dependency licenses and vulnerabilities before redistribution or deployment. No third party endorsement or organizational affiliation is implied.
