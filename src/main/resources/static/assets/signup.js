const TOKEN_KEY = "nexora_access_token";
const API_ROOT = window.NexoraApi?.root?.() || "/api";
const params = new URLSearchParams(window.location.search);

const state = {
    plans: [],
    selectedPlan: null,
    paymentMethods: [],
    selectedPaymentMethod: null,
    pendingPayment: null,
    pendingEmail: null
};

const elements = {
    form: document.querySelector("#signupForm"),
    intro: document.querySelector("#signupIntro"),
    success: document.querySelector("#signupSuccess"),
    formAlert: document.querySelector("#formAlert"),
    otpPanel: document.querySelector("#otpPanel"),
    otpInput: document.querySelector("#otpInput"),
    otpEmail: document.querySelector("#otpEmail"),
    resendOtpButton: document.querySelector("#resendOtpButton"),
    submitButton: document.querySelector("#createAccountButton"),
    submitLabel: document.querySelector("#submitLabel"),
    password: document.querySelector("#passwordInput"),
    passwordConfirmation: document.querySelector("#passwordConfirmation"),
    passwordQuality: document.querySelector(".password-quality"),
    passwordQualityText: document.querySelector("#passwordQuality"),
    termsError: document.querySelector("#termsError"),
    stepPlanName: document.querySelector("#stepPlanName"),
    stepConfirmationLabel: document.querySelector("#stepConfirmationLabel"),
    planName: document.querySelector("#summaryTitle"),
    planMonogram: document.querySelector("#planMonogram"),
    planPrice: document.querySelector("#planPrice"),
    planPeriod: document.querySelector("#planPeriod"),
    trialTitle: document.querySelector("#trialTitle"),
    trialCopy: document.querySelector("#trialCopy"),
    billingNote: document.querySelector("#billingNote"),
    summaryToday: document.querySelector("#summaryToday"),
    summaryFeatures: document.querySelector("#summaryFeatures"),
    paymentPanel: document.querySelector("#paymentPanel"),
    paymentMethods: document.querySelector("#paymentMethods"),
    paymentProof: document.querySelector("#paymentProofInput"),
    changePlanButton: document.querySelector("#changePlanButton"),
    planDialog: document.querySelector("#planDialog"),
    closePlanDialog: document.querySelector("#closePlanDialog"),
    planPicker: document.querySelector("#planPicker"),
    successName: document.querySelector("#successName"),
    successOrganization: document.querySelector("#successOrganization"),
    successPlan: document.querySelector("#successPlan"),
    successCopy: document.querySelector("#successCopy"),
    successStatus: document.querySelector("#successStatus"),
    successPeriodLabel: document.querySelector("#successPeriodLabel"),
    successPeriodValue: document.querySelector("#successPeriodValue")
};

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#039;");
}

function apiUrl(path) {
    return window.NexoraApi?.url ? window.NexoraApi.url(path) : `${API_ROOT}${path}`;
}

function formatPrice(plan) {
    const price = Number(plan.priceMonthly || 0);
    if (price === 0) return "Gratuit";
    const amount = new Intl.NumberFormat("fr-FR", { maximumFractionDigits: 0 }).format(price);
    const currency = String(plan.currency || "FCFA").toUpperCase();
    return `${amount} ${currency === "XOF" || currency === "XAF" ? "FCFA" : currency}`;
}

function isPaidPlan(plan) {
    return Number(plan?.priceMonthly || 0) > 0;
}

function formatDate(value) {
    if (!value) return null;
    const date = value instanceof Date ? value : new Date(value);
    if (Number.isNaN(date.getTime())) return null;
    return new Intl.DateTimeFormat("fr-FR", { dateStyle: "long" }).format(date);
}

function expectedTrialEnd(plan) {
    const trialDays = Number(plan?.trialDays || 0);
    if (Number(plan?.priceMonthly || 0) <= 0 || trialDays <= 0) return null;
    const date = new Date();
    date.setDate(date.getDate() + trialDays);
    return date;
}

function expectedPeriodEnd(plan) {
    const days = Number(plan?.billingPeriodDays || 30);
    if (days <= 0) return null;
    const date = new Date();
    date.setDate(date.getDate() + days);
    return date;
}

function planFeatures(plan) {
    const features = [
        `${plan.maxUsers} utilisateur${plan.maxUsers > 1 ? "s" : ""}`,
        `${plan.maxIptvAccounts} source${plan.maxIptvAccounts > 1 ? "s" : ""} IPTV`,
        `${plan.maxConcurrentStreams} stream${plan.maxConcurrentStreams > 1 ? "s" : ""} simultané${plan.maxConcurrentStreams > 1 ? "s" : ""}`,
        `${plan.storageGb} Go de stockage de service`
    ];
    if (plan.accessSummary) features.unshift(plan.accessSummary);
    return features;
}

function renderSelectedPlan() {
    const plan = state.selectedPlan;
    if (!plan) return;

    const paid = isPaidPlan(plan);
    const free = !paid;
    const periodDays = Number(plan.billingPeriodDays || 30);
    const trialEnd = formatDate(expectedTrialEnd(plan));
    const periodEnd = formatDate(expectedPeriodEnd(plan));
    elements.stepPlanName.textContent = plan.name;
    elements.planName.textContent = plan.name;
    elements.planMonogram.textContent = plan.name.charAt(0).toUpperCase();
    elements.planPrice.textContent = formatPrice(plan);
    elements.planPeriod.textContent = `/${periodDays} jours`;
    elements.summaryFeatures.innerHTML = planFeatures(plan)
        .map((feature) => `<li>${escapeHtml(feature)}</li>`)
        .join("");
    elements.trialTitle.textContent = free ? `${periodDays} jours gratuits` : "Paiement manuel requis";
    elements.trialCopy.textContent = free
        ? "Une seule periode gratuite par compte."
        : "Validation admin avant activation.";
    elements.billingNote.textContent = free
        ? `Fin de la periode gratuite prevue le ${periodEnd || "selon la duree configuree"}.`
        : "Votre espace sera active apres verification du paiement.";
    if (paid && trialEnd) {
        elements.billingNote.textContent = `Le paiement est verifie avant activation. Duree de formule: ${periodDays} jours.`;
    }
    elements.summaryToday.textContent = free ? "0 FCFA" : formatPrice(plan);
    elements.paymentPanel.hidden = !paid;
    elements.paymentProof.required = paid;
    elements.stepConfirmationLabel.textContent = paid ? "Validation admin" : "Acces immediat";
    elements.submitLabel.textContent = free
        ? "Créer mon compte gratuit"
        : "Creer mon compte et envoyer le paiement";
    if (paid) {
        renderPaymentMethods();
    }

    const url = new URL(window.location.href);
    url.searchParams.set("plan", plan.code);
    window.history.replaceState({}, "", url);
    renderPlanPicker();
}

function renderPaymentMethods() {
    if (!elements.paymentMethods) return;
    if (!state.paymentMethods.length) {
        state.selectedPaymentMethod = null;
        elements.paymentMethods.innerHTML = `<div class="payment-empty">Aucun moyen de paiement actif. Contactez le support.</div>`;
        return;
    }
    if (!state.paymentMethods.some((method) => method.code === state.selectedPaymentMethod)) {
        state.selectedPaymentMethod = state.paymentMethods[0].code;
    }
    elements.paymentMethods.innerHTML = state.paymentMethods.map((method) => `
        <button class="payment-method ${method.code === state.selectedPaymentMethod ? "selected" : ""}" type="button" data-payment-method="${escapeHtml(method.code)}">
            <span>${escapeHtml(method.name)}</span>
            <small>${escapeHtml(method.instructions || "Envoyez le paiement puis indiquez la reference.")}</small>
        </button>
    `).join("");
}

function renderPlanPicker() {
    elements.planPicker.innerHTML = state.plans.map((plan) => `
        <button class="picker-plan ${plan.code === state.selectedPlan?.code ? "selected" : ""}" type="button" data-plan="${escapeHtml(plan.code)}">
            <small>${plan.code === "pro" ? "RECOMMANDÉ" : "FORMULE"}</small>
            <h3>${escapeHtml(plan.name)}</h3>
            <strong>${escapeHtml(formatPrice(plan))}</strong>
            <span>${Number(plan.priceMonthly || 0) === 0 ? "sans limite de durée" : "par mois"}</span>
            <ul>
                ${planFeatures(plan).slice(0, 3).map((feature) => `<li>✓ ${escapeHtml(feature)}</li>`).join("")}
            </ul>
        </button>
    `).join("");
}

async function loadPlans() {
    const [plansResponse, methodsResponse] = await Promise.all([
        fetch(apiUrl("/billing/plans"), { headers: { Accept: "application/json" } }),
        fetch(apiUrl("/billing/payment-methods"), { headers: { Accept: "application/json" } })
    ]);
    const body = await plansResponse.json().catch(() => ({}));
    const methodsBody = await methodsResponse.json().catch(() => ({}));
    if (!plansResponse.ok || body.success === false || !Array.isArray(body.data) || !body.data.length) {
        throw new Error("Les formules sont momentanément indisponibles.");
    }

    state.plans = body.data.filter((plan) => plan.active);
    state.paymentMethods = methodsResponse.ok && methodsBody.success !== false && Array.isArray(methodsBody.data)
        ? methodsBody.data.filter((method) => method.active)
        : [];
    const requestedCode = params.get("plan");
    state.selectedPlan = state.plans.find((plan) => plan.code === requestedCode)
        || state.plans.find((plan) => plan.code === "basic")
        || state.plans[0];
    renderSelectedPlan();
}

function passwordScore(value) {
    if (!value) return 0;
    let score = value.length >= 8 ? 1 : 0;
    if (/[a-z]/.test(value) && /[A-Z]/.test(value)) score += 1;
    if (/\d/.test(value)) score += 1;
    if (/[^A-Za-z0-9]/.test(value) || value.length >= 12) score += 1;
    return Math.min(score, 4);
}

function updatePasswordQuality() {
    const score = passwordScore(elements.password.value);
    elements.passwordQuality.dataset.score = String(score);
    elements.passwordQualityText.textContent = [
        "Utilisez au moins 8 caractères, une majuscule et un chiffre.",
        "Mot de passe encore trop simple.",
        "Sécurité moyenne. Ajoutez un symbole ou davantage de caractères.",
        "Bon mot de passe.",
        "Excellent mot de passe."
    ][score];
}

function clearErrors() {
    document.querySelectorAll(".signup-field.invalid").forEach((field) => field.classList.remove("invalid"));
    document.querySelectorAll(".field-error").forEach((error) => {
        error.textContent = "";
    });
    elements.termsError.textContent = "";
    elements.formAlert.hidden = true;
}

function showFieldError(name, message) {
    const field = document.querySelector(`[data-field="${name}"]`);
    if (!field) return;
    field.classList.add("invalid");
    field.querySelector(".field-error").textContent = message;
}

function validateOtp(formData) {
    const code = String(formData.get("code") || "").trim();
    if (!/^\d{6}$/.test(code)) {
        showFieldError("code", "Saisissez le code OTP a 6 chiffres.");
        return false;
    }
    return true;
}

function validateForm(formData) {
    let valid = true;
    const name = String(formData.get("name") || "").trim();
    const organizationName = String(formData.get("organizationName") || "").trim();
    const email = String(formData.get("email") || "").trim();
    const password = String(formData.get("password") || "");
    const confirmation = String(formData.get("passwordConfirmation") || "");

    if (name.length < 2) {
        showFieldError("name", "Indiquez votre nom complet.");
        valid = false;
    }
    if (organizationName.length < 2) {
        showFieldError("organizationName", "Donnez un nom à votre espace.");
        valid = false;
    }
    if (!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)) {
        showFieldError("email", "Saisissez une adresse e-mail valide.");
        valid = false;
    }
    if (password.length < 8) {
        showFieldError("password", "Le mot de passe doit contenir au moins 8 caractères.");
        valid = false;
    }
    if (confirmation !== password) {
        showFieldError("passwordConfirmation", "Les deux mots de passe ne correspondent pas.");
        valid = false;
    }
    if (!formData.has("terms")) {
        elements.termsError.textContent = "Votre accord est nécessaire pour créer le compte.";
        valid = false;
    }
    if (isPaidPlan(state.selectedPlan)) {
        const proof = String(formData.get("paymentProof") || "").trim();
        if (!state.selectedPaymentMethod) {
            elements.formAlert.textContent = "Aucun moyen de paiement actif n'est configure.";
            elements.formAlert.hidden = false;
            valid = false;
        }
        if (proof.length < 3) {
            showFieldError("paymentProof", "Indiquez la reference ou un lien vers la preuve de paiement.");
            valid = false;
        }
    }
    return valid;
}

async function submitSignup(event) {
    event.preventDefault();
    clearErrors();
    const formData = new FormData(elements.form);
    if (state.pendingEmail) {
        if (!validateOtp(formData)) return;
    } else if (!validateForm(formData) || !state.selectedPlan) {
        return;
    }

    elements.submitButton.classList.add("loading");
    elements.submitButton.disabled = true;

    try {
        const response = state.pendingEmail
            ? await fetch(apiUrl("/auth/email/verify"), {
                method: "POST",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    email: state.pendingEmail,
                    code: String(formData.get("code")).trim()
                })
            })
            : await fetch(apiUrl("/auth/register"), {
                method: "POST",
                headers: {
                    Accept: "application/json",
                    "Content-Type": "application/json"
                },
                body: JSON.stringify({
                    name: String(formData.get("name")).trim(),
                    organizationName: String(formData.get("organizationName")).trim(),
                    email: String(formData.get("email")).trim(),
                    password: String(formData.get("password")),
                    planCode: state.selectedPlan.code,
                    paymentMethodCode: isPaidPlan(state.selectedPlan) ? state.selectedPaymentMethod : null,
                    paymentProof: isPaidPlan(state.selectedPlan) ? String(formData.get("paymentProof") || "").trim() : null
                })
            });
        const body = await response.json().catch(() => ({}));
        if (!response.ok || body.success === false) {
            (body.errors || []).forEach((error) => showFieldError(error.field, error.message));
            throw new Error(body.message === "Validation failed"
                ? "Vérifiez les informations indiquées."
                : body.message || "La création du compte a échoué.");
        }

        if (body.data?.requiresEmailVerification) {
            showEmailVerification(body.data);
            return;
        }

        localStorage.setItem(TOKEN_KEY, body.data.token);
        showSuccess(body.data);
    } catch (error) {
        elements.formAlert.textContent = error.message || "Une erreur réseau empêche la création du compte.";
        elements.formAlert.hidden = false;
        elements.formAlert.scrollIntoView({ behavior: "smooth", block: "center" });
    } finally {
        elements.submitButton.classList.remove("loading");
        elements.submitButton.disabled = false;
    }
}

function showEmailVerification(data) {
    state.pendingEmail = data.email;
    state.pendingPayment = data.payment || null;
    elements.otpEmail.textContent = data.email;
    elements.otpPanel.hidden = false;
    elements.otpInput.required = true;
    elements.submitLabel.textContent = "Verifier mon email";
    const steps = document.querySelectorAll(".signup-steps .step");
    steps[1]?.classList.add("complete");
    steps[2]?.classList.add("active");
    elements.formAlert.textContent = data.message || "Code OTP envoye. Verifiez votre boite email.";
    elements.formAlert.hidden = false;
    elements.otpInput.focus();
    elements.otpPanel.scrollIntoView({ behavior: "smooth", block: "center" });
}

async function resendOtp() {
    if (!state.pendingEmail) return;
    elements.resendOtpButton.disabled = true;
    try {
        const response = await fetch(apiUrl("/auth/email/resend"), {
            method: "POST",
            headers: {
                Accept: "application/json",
                "Content-Type": "application/json"
            },
            body: JSON.stringify({ email: state.pendingEmail })
        });
        const body = await response.json().catch(() => ({}));
        if (!response.ok || body.success === false) {
            throw new Error(body.message || "Impossible de renvoyer le code OTP.");
        }
        elements.formAlert.textContent = body.message || "Code OTP renvoye.";
        elements.formAlert.hidden = false;
    } catch (error) {
        elements.formAlert.textContent = error.message || "Impossible de renvoyer le code OTP.";
        elements.formAlert.hidden = false;
    } finally {
        elements.resendOtpButton.disabled = false;
    }
}

function showSuccess(data) {
    const subscription = data.subscription || {};
    const payment = data.payment || state.pendingPayment || null;
    const paymentPending = String(payment?.status || "").toUpperCase() === "PENDING";
    const trialEnd = formatDate(subscription.trialEndsAt);
    const periodEnd = formatDate(subscription.currentPeriodEnd);
    elements.form.hidden = true;
    elements.intro.hidden = true;
    elements.successName.textContent = data.user?.name?.split(" ")[0] || "vous";
    elements.successCopy.innerHTML = paymentPending
        ? `Votre compte est cree pour <strong>${escapeHtml(data.organization?.name || "Nexora")}</strong>. La formule <strong>${escapeHtml(data.subscription?.plan?.name || state.selectedPlan.name)}</strong> sera activee apres validation du paiement.`
        : `Votre espace <strong id="successOrganization">${escapeHtml(data.organization?.name || "Nexora")}</strong> et votre formule <strong id="successPlan">${escapeHtml(data.subscription?.plan?.name || state.selectedPlan.name)}</strong> sont prets.`;
    elements.successStatus.textContent = paymentPending
        ? "Paiement en attente"
        : data.subscription?.status === "TRIALING"
            ? "Essai activé"
            : "Actif";
    if (paymentPending) {
        elements.successPeriodLabel.textContent = "REFERENCE";
        elements.successPeriodValue.textContent = payment.reference || "Paiement en attente";
    } else if (subscription.status === "TRIALING" && trialEnd) {
        elements.successPeriodLabel.textContent = "FIN D'ESSAI";
        elements.successPeriodValue.textContent = trialEnd;
    } else if (periodEnd) {
        elements.successPeriodLabel.textContent = "FIN DE PÉRIODE";
        elements.successPeriodValue.textContent = periodEnd;
    } else {
        elements.successPeriodLabel.textContent = "PROCHAINE ÉTAPE";
        elements.successPeriodValue.textContent = "Ajouter vos sources";
    }
    elements.success.hidden = false;
    document.querySelectorAll(".signup-steps .step").forEach((step) => step.classList.add("complete"));
    elements.success.scrollIntoView({ behavior: "smooth", block: "center" });
}

elements.form.addEventListener("submit", submitSignup);
elements.resendOtpButton.addEventListener("click", resendOtp);
elements.password.addEventListener("input", updatePasswordQuality);
document.querySelectorAll("[data-password-toggle]").forEach((button) => {
    button.addEventListener("click", () => {
        const input = document.getElementById(button.dataset.passwordToggle);
        const visible = input.type === "text";
        input.type = visible ? "password" : "text";
        button.textContent = visible ? "Voir" : "Masquer";
        button.setAttribute("aria-label", visible ? "Afficher le mot de passe" : "Masquer le mot de passe");
    });
});
elements.changePlanButton.addEventListener("click", () => elements.planDialog.showModal());
elements.closePlanDialog.addEventListener("click", () => elements.planDialog.close());
elements.planDialog.addEventListener("click", (event) => {
    if (event.target === elements.planDialog) elements.planDialog.close();
});
elements.planPicker.addEventListener("click", (event) => {
    const button = event.target.closest("[data-plan]");
    if (!button) return;
    state.selectedPlan = state.plans.find((plan) => plan.code === button.dataset.plan);
    renderSelectedPlan();
    elements.planDialog.close();
});
elements.paymentMethods.addEventListener("click", (event) => {
    const button = event.target.closest("[data-payment-method]");
    if (!button) return;
    state.selectedPaymentMethod = button.dataset.paymentMethod;
    renderPaymentMethods();
});

const email = params.get("email");
if (email) elements.form.elements.email.value = email;
updatePasswordQuality();
loadPlans().catch((error) => {
    elements.formAlert.textContent = error.message;
    elements.formAlert.hidden = false;
    elements.submitButton.disabled = true;
});
