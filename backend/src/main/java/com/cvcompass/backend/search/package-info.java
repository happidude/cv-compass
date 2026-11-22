@org.springframework.modulith.ApplicationModule(
        allowedDependencies = {
            "candidate::catalog-links",
            "candidate::metrics",
            "candidate::events",
            "catalog::lookup",
            "mapping::results",
            "web"
        })
package com.cvcompass.backend.search;
